package com.gizmodata.quack.jdbc.transport;

import com.gizmodata.quack.jdbc.QuackException;
import com.gizmodata.quack.jdbc.QuackProtocolException;
import com.gizmodata.quack.jdbc.QuackServerException;
import com.gizmodata.quack.jdbc.codec.DecodeLimits;
import com.gizmodata.quack.jdbc.codec.HugeIntParts;
import com.gizmodata.quack.jdbc.message.MessageCodec;
import com.gizmodata.quack.jdbc.message.MessageHeader;
import com.gizmodata.quack.jdbc.message.MessageType;
import com.gizmodata.quack.jdbc.message.QuackMessage;
import com.gizmodata.quack.jdbc.type.LogicalType;
import com.gizmodata.quack.jdbc.type.LogicalTypeId;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.Authenticator;
import java.net.ConnectException;
import java.net.CookieHandler;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.ClosedChannelException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

@Timeout(10)
class QuackHttpTransportLimitsTest {
    private static final URI ENDPOINT = URI.create("http://localhost:9494/quack");
    private static final QuackMessage REQUEST = new QuackMessage.DisconnectMessage(
            MessageHeader.of(MessageType.DISCONNECT_MESSAGE).withClientQueryId(1));
    private static final QuackMessage SUCCESS = new QuackMessage.SuccessResponse(
            MessageHeader.of(MessageType.SUCCESS_RESPONSE).withClientQueryId(1));

    private static DecodeLimits responseLimit(int bytes) {
        return new DecodeLimits(bytes, DecodeLimits.DEFAULT.maxDecodedBytes(),
                DecodeLimits.DEFAULT.maxNestingDepth());
    }

    private static QuackHttpTransport transport(StubClient client, DecodeLimits limits) {
        return new QuackHttpTransport(ENDPOINT, client, Duration.ofSeconds(2),
                Map.of("X-Proxy-Auth", "test-auth"), limits);
    }

    @ParameterizedTest
    @CsvSource({"false,0", "true,0", "false,1", "true,1"})
    void realHttpBoundsDeclaredAndChunkedBodies(boolean chunked, int excessBytes) throws Exception {
        QuackMessage expected = new QuackMessage.SuccessResponse(SUCCESS.header()
                .withConnectionId("x".repeat(20000)));
        byte[] bytes = MessageCodec.encode(expected);
        int limit = bytes.length - excessBytes;
        AtomicInteger hits = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/quack", exchange -> {
            hits.incrementAndGet();
            try (exchange) {
                exchange.getRequestBody().transferTo(java.io.OutputStream.nullOutputStream());
                exchange.sendResponseHeaders(200, chunked ? 0 : bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
        server.start();
        try {
            QuackUri uri = QuackUri.parse("jdbc:quack://127.0.0.1:" + server.getAddress().getPort()
                    + "?maxResponseBytes=" + limit);
            QuackHttpTransport transport = QuackHttpTransport.from(uri);
            if (excessBytes == 0) {
                assertEquals(expected, transport.send(REQUEST));
            } else {
                QuackException error = assertThrows(QuackException.class, () -> transport.send(REQUEST));
                assertTrue(error.getMessage().contains("maxResponseBytes limit of " + limit));
            }
            assertEquals(1, hits.get());
        } finally {
            server.stop(0);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 8192, 20000})
    void exactLimitAndFinalCopyReturnOnlyActualBytesAndClose(int padding) {
        QuackMessage expected = new QuackMessage.SuccessResponse(SUCCESS.header()
                .withConnectionId("x".repeat(padding + 1)));
        byte[] bytes = MessageCodec.encode(expected);
        for (int limit : new int[]{bytes.length, bytes.length + 1, Integer.MAX_VALUE}) {
            StubClient client = new StubClient(bytes);

            assertEquals(expected, transport(client, responseLimit(limit)).send(REQUEST));

            assertTrue(client.body.closed);
            assertEquals(bytes.length, client.body.position);
            assertEquals("POST", client.request.method());
            assertEquals("test-auth", client.request.headers().firstValue("X-Proxy-Auth").orElseThrow());
        }
    }

    @Test
    void oversizedContentLengthIsRejectedWithoutReadingOrReplaying() {
        StubClient client = new StubClient(new byte[0]);
        client.headers = Map.of("Content-Length", List.of(Long.toString(Long.MAX_VALUE)));

        QuackException error = assertThrows(QuackException.class,
                () -> transport(client, responseLimit(16)).send(REQUEST));

        assertTrue(error.getMessage().contains("maxResponseBytes limit of 16"));
        assertEquals(0, client.body.readCalls);
        assertTrue(client.body.closed);
        assertEquals(1, client.sends);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 16, 8192, 20000})
    void actualReadsStopAtLimitPlusOneEvenWithMissingOrUnderstatedLength(int limit) {
        for (Map<String, List<String>> headers : List.of(Map.<String, List<String>>of(),
                Map.of("Content-Length", List.of("1")))) {
            StubClient client = new StubClient(new byte[limit + 100]);
            client.headers = headers;

            QuackException error = assertThrows(QuackException.class,
                    () -> transport(client, responseLimit(limit)).send(REQUEST));

            assertTrue(error.getMessage().contains("maxResponseBytes limit of " + limit));
            assertEquals(limit + 1, client.body.position);
            assertTrue(client.body.closed);
            assertEquals(1, client.sends);
        }
    }

    @Test
    void hugeErrorBodyIsNotReadAndCloseFailureDoesNotHideStatusOrDetail() {
        StubClient client = new StubClient(new byte[0]);
        client.status = 503;
        client.headers = Map.of("Content-Length", List.of(Long.toString(Long.MAX_VALUE)),
                "EXCEPTION_WHAT", List.of("server detail"));
        client.body.closeFailure = new ClosedChannelException();

        QuackException error = assertThrows(QuackException.class,
                () -> transport(client, responseLimit(1)).send(REQUEST));

        assertTrue(error.getMessage().contains("Quack HTTP returned status 503"));
        assertTrue(error.getMessage().endsWith(": server detail"));
        assertEquals(0, client.body.readCalls);
        assertTrue(client.body.closed);
        assertEquals(1, client.sends);
        assertArrayEquals(new Throwable[]{client.body.closeFailure}, error.getSuppressed());
    }

    @Test
    void realHttpReturnsErrorWithoutWaitingForUnusedHugeBody() throws Exception {
        CountDownLatch releaseBody = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/quack", exchange -> {
            try (exchange) {
                exchange.getRequestBody().transferTo(java.io.OutputStream.nullOutputStream());
                exchange.getResponseHeaders().add("EXCEPTION_WHAT", "server detail");
                exchange.sendResponseHeaders(503, Long.MAX_VALUE);
                releaseBody.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        server.start();
        try {
            QuackHttpTransport transport = new QuackHttpTransport(URI.create("http://127.0.0.1:"
                    + server.getAddress().getPort() + "/quack"));

            QuackException error = assertTimeoutPreemptively(Duration.ofSeconds(3),
                    () -> assertThrows(QuackException.class, () -> transport.send(REQUEST)));

            assertTrue(error.getMessage().contains("Quack HTTP returned status 503"));
            assertTrue(error.getMessage().endsWith(": server detail"));
        } finally {
            releaseBody.countDown();
            server.stop(0);
        }
    }

    static Stream<IOException> ioFailures() {
        return Stream.of(new IOException("body read failed"), new ClosedChannelException(),
                new ConnectException("body connection failure"), new HttpConnectTimeoutException("body timeout"));
    }

    @ParameterizedTest
    @MethodSource("ioFailures")
    void bodyReadFailuresNeverEnterConnectFallback(IOException failure) {
        StubClient client = new StubClient(MessageCodec.encode(SUCCESS));
        client.body.readFailure = failure;

        QuackException error = assertThrows(QuackException.class,
                () -> transport(client, DecodeLimits.DEFAULT).send(REQUEST));

        assertSame(failure, error.getCause());
        assertTrue(error.getMessage().startsWith("Quack HTTP request to "));
        assertTrue(client.body.closed);
        assertEquals(1, client.sends);
    }

    @Test
    void bodyCloseFailuresNeverEnterConnectFallback() {
        StubClient client = new StubClient(MessageCodec.encode(SUCCESS));
        client.body.closeFailure = new ClosedChannelException();

        QuackException error = assertThrows(QuackException.class,
                () -> transport(client, DecodeLimits.DEFAULT).send(REQUEST));

        assertSame(client.body.closeFailure, error.getCause());
        assertTrue(error.getMessage().startsWith("Quack HTTP request to "));
        assertTrue(client.body.closed);
        assertEquals(1, client.sends);
    }

    @Test
    void decodeFailureClosesBodyWithoutReplaying() {
        StubClient client = new StubClient(new byte[]{0});

        assertThrows(QuackProtocolException.class,
                () -> transport(client, DecodeLimits.DEFAULT).send(REQUEST));

        assertTrue(client.body.closed);
        assertEquals(1, client.sends);
    }

    @Test
    void protocolErrorStillRaisesServerExceptionAndClosesBody() {
        StubClient client = new StubClient(MessageCodec.encode(new QuackMessage.ErrorResponse(
                MessageHeader.of(MessageType.ERROR_RESPONSE), "query failed")));

        QuackServerException error = assertThrows(QuackServerException.class,
                () -> transport(client, DecodeLimits.DEFAULT).send(REQUEST));

        assertEquals("query failed", error.getMessage());
        assertTrue(client.body.closed);
        assertEquals(1, client.sends);
    }

    @Test
    void customAllocationAndNestingLimitsReachMessageDecoder() {
        QuackMessage message = new QuackMessage.PrepareResponse(
                MessageHeader.of(MessageType.PREPARE_RESPONSE).withConnectionId("connection-id"),
                List.of(LogicalType.of(LogicalTypeId.INTEGER)), List.of("column"), false,
                List.of(), new HugeIntParts(0, 0));
        byte[] bytes = MessageCodec.encode(message);
        assertEquals(message, transport(new StubClient(bytes), DecodeLimits.DEFAULT).send(REQUEST));
        for (DecodeLimits limits : List.of(new DecodeLimits(bytes.length, 1, 64),
                new DecodeLimits(bytes.length, DecodeLimits.DEFAULT.maxDecodedBytes(), 1))) {
            StubClient client = new StubClient(bytes);

            assertThrows(QuackProtocolException.class, () -> transport(client, limits).send(REQUEST));

            assertTrue(client.body.closed);
            assertEquals(1, client.sends);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {3, 4})
    void existingInjectableConstructorsUseDefaultLimits(int arguments) throws Exception {
        assertNotNull(QuackHttpTransport.class.getConstructor(URI.class));
        StubClient client = new StubClient(new byte[0]);
        client.headers = Map.of("Content-Length",
                List.of(Long.toString((long) DecodeLimits.DEFAULT.maxResponseBytes() + 1)));
        QuackHttpTransport transport = arguments == 3
                ? new QuackHttpTransport(ENDPOINT, client, Duration.ofSeconds(2))
                : new QuackHttpTransport(ENDPOINT, client, Duration.ofSeconds(2), Map.of());

        QuackException error = assertThrows(QuackException.class, () -> transport.send(REQUEST));

        assertTrue(error.getMessage().contains("maxResponseBytes limit of "
                + DecodeLimits.DEFAULT.maxResponseBytes()));
        assertEquals(0, client.body.readCalls);
        assertTrue(client.body.closed);
    }

    @Test
    void invalidDirectExtraHeadersFailSafelyBeforeSending() {
        String secret = "B40_SYNTHETIC_HEADER_CREDENTIAL";
        for (Map<String, String> headers : List.of(Map.of("Authorization", secret + "\u0001suffix"),
                Map.of("Authorization", secret + "\u007fsuffix"), Map.of("Authorization", secret + "\u0100suffix"),
                Map.of("Bad/" + secret, "value"), Map.of("Connection", secret))) {
            StubClient client = new StubClient(MessageCodec.encode(SUCCESS));
            QuackHttpTransport transport = new QuackHttpTransport(URI.create("https://example.test/quack"),
                    client, Duration.ofSeconds(2), headers);
            QuackException error = assertThrows(QuackException.class, () -> transport.send(REQUEST));
            StringWriter trace = new StringWriter();
            error.printStackTrace(new PrintWriter(trace));
            assertFalse(trace.toString().contains(secret));
            assertNull(error.getCause(), "JDK header exceptions contain the rejected input");
            assertEquals(0, client.sends);
        }
        StubClient client = new StubClient(MessageCodec.encode(SUCCESS));
        Map<String, String> valid = Map.of("X-!#$%&'*+-.^_`|~", secret + " :/==\tend\u00e9");
        QuackHttpTransport transport = new QuackHttpTransport(URI.create("https://example.test/quack"),
                client, Duration.ofSeconds(2), valid);
        assertEquals(SUCCESS, transport.send(REQUEST));
        assertEquals(valid.values().iterator().next(), client.request.headers().firstValue(valid.keySet().iterator().next()).orElseThrow());
    }

    @Test
    void connectFailuresStillTryNextAddress() {
        StubClient client = new StubClient(MessageCodec.encode(SUCCESS));
        client.sendFailure = new ConnectException("first address unavailable");
        QuackHttpTransport transport = transport(client, DecodeLimits.DEFAULT);
        assumeTrue(transport.endpointCandidates().length > 1, "requires multiple localhost addresses");

        assertEquals(SUCCESS, transport.send(REQUEST));

        assertEquals(2, client.sends);
        assertTrue(client.body.closed);
    }

    private static final class TrackedBody extends InputStream {
        private final byte[] bytes;
        private int position;
        private int readCalls;
        private boolean closed;
        private IOException readFailure;
        private IOException closeFailure;

        private TrackedBody(byte[] bytes) { this.bytes = bytes; }

        @Override
        public int read() throws IOException {
            readCalls++;
            if (readFailure != null) throw readFailure;
            return position == bytes.length ? -1 : Byte.toUnsignedInt(bytes[position++]);
        }

        @Override
        public int read(byte[] target, int offset, int length) throws IOException {
            readCalls++;
            if (readFailure != null) throw readFailure;
            if (length == 0) return 0;
            if (position == bytes.length) return -1;
            int count = Math.min(length, bytes.length - position);
            System.arraycopy(bytes, position, target, offset, count);
            position += count;
            return count;
        }

        @Override
        public void close() throws IOException {
            closed = true;
            if (closeFailure != null) throw closeFailure;
        }
    }

    private static final class StubClient extends HttpClient {
        private final TrackedBody body;
        private int sends;
        private int status = 200;
        private Map<String, List<String>> headers = Map.of();
        private HttpRequest request;
        private IOException sendFailure;

        private StubClient(byte[] bytes) { body = new TrackedBody(bytes); }

        @Override
        @SuppressWarnings("unchecked")
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
                throws IOException {
            this.request = request;
            sends++;
            if (sends == 1 && sendFailure != null) throw sendFailure;
            return new StubResponse<>(request, (T) body, status, HttpHeaders.of(headers, (k, v) -> true));
        }

        @Override public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }
        @Override public Optional<Duration> connectTimeout() { return Optional.empty(); }
        @Override public Redirect followRedirects() { return Redirect.NEVER; }
        @Override public Optional<ProxySelector> proxy() { return Optional.empty(); }
        @Override public SSLContext sslContext() { throw new UnsupportedOperationException(); }
        @Override public SSLParameters sslParameters() { throw new UnsupportedOperationException(); }
        @Override public Optional<Authenticator> authenticator() { return Optional.empty(); }
        @Override public Version version() { return Version.HTTP_1_1; }
        @Override public Optional<Executor> executor() { return Optional.empty(); }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            throw new UnsupportedOperationException();
        }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(
                HttpRequest request, HttpResponse.BodyHandler<T> handler,
                HttpResponse.PushPromiseHandler<T> pushPromiseHandler) {
            throw new UnsupportedOperationException();
        }
    }

    private record StubResponse<T>(HttpRequest request, T body, int statusCode, HttpHeaders headers)
            implements HttpResponse<T> {
        @Override public Optional<HttpResponse<T>> previousResponse() { return Optional.empty(); }
        @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
        @Override public URI uri() { return request.uri(); }
        @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
    }
}

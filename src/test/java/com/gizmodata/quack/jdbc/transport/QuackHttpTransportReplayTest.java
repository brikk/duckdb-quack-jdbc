package com.gizmodata.quack.jdbc.transport;

import com.gizmodata.quack.jdbc.QuackException;
import com.gizmodata.quack.jdbc.codec.HugeIntParts;
import com.gizmodata.quack.jdbc.message.DataChunk;
import com.gizmodata.quack.jdbc.message.DecodedVector;
import com.gizmodata.quack.jdbc.message.MessageCodec;
import com.gizmodata.quack.jdbc.message.MessageHeader;
import com.gizmodata.quack.jdbc.message.MessageType;
import com.gizmodata.quack.jdbc.message.QuackMessage;
import com.gizmodata.quack.jdbc.type.LogicalType;
import com.gizmodata.quack.jdbc.type.LogicalTypeId;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.ConnectException;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.ClosedChannelException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Deterministic address-fallback checks without external DNS, IPv6, or real HTTP/SQL. */
public class QuackHttpTransportReplayTest {
    private static final URI ENDPOINT = URI.create("http://quack-replay.test:9494/quack");

    @ParameterizedTest
    @CsvSource({"1,prepare", "3,prepare", "1,fetch", "3,fetch", "1,append", "3,send-data",
            "3,heartbeat", "1,disconnect", "3,disconnect", "3,cancel"})
    void closedChannelNeverReplaysUncertainRequests(int version, String kind) throws Exception {
        runChild("closed", version, kind);
    }

    @ParameterizedTest
    @CsvSource({"connect,1,prepare", "connect,3,prepare", "connect-timeout,1,prepare",
            "connect-timeout,3,prepare", "connect,3,fetch", "connect-timeout,3,send-data"})
    void genuineConnectFailuresStillFallThrough(String mode, int version, String kind) throws Exception {
        runChild(mode, version, kind);
    }

    @ParameterizedTest
    @CsvSource({"io,1,prepare", "io,3,fetch", "body-closed,1,prepare", "body-closed,3,fetch",
            "connect,3,cancel", "connect-timeout,3,disconnect"})
    void otherFailuresAndControlsRemainSingleAttempt(String mode, int version, String kind) throws Exception {
        runChild(mode, version, kind);
    }

    private static void runChild(String mode, int version, String kind) throws Exception {
        Path directory = Files.createTempDirectory(Path.of("target"), "quack-replay-").toAbsolutePath();
        Path hosts = directory.resolve("hosts");
        Files.writeString(hosts, "127.0.0.1 quack-replay.test\n127.0.0.2 quack-replay.test\n");
        Path log = directory.resolve("child.log");
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Djdk.net.hosts.file=" + hosts, "-cp",
                System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                QuackHttpTransportReplayTest.class.getName(), mode, Integer.toString(version), kind)
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            assertTrue(child.waitFor(15, TimeUnit.SECONDS), "Replay check timed out; see " + log);
            String output = Files.readString(log);
            System.out.print(output);
            assertEquals(0, child.exitValue(), output);
            assertTrue(output.contains("PASS " + mode + "/v" + version + "/" + kind), output);
        } finally {
            child.destroyForcibly();
            child.waitFor(5, TimeUnit.SECONDS);
        }
    }

    public static void main(String[] args) {
        String mode = args[0];
        int version = Integer.parseInt(args[1]);
        String kind = args[2];
        IOException failure = switch (mode) {
            case "closed", "body-closed" -> new ClosedChannelException();
            case "connect" -> new ConnectException("test connect failure");
            case "connect-timeout" -> new HttpConnectTimeoutException("test connect timeout");
            case "io" -> new IOException("test ambiguous I/O failure");
            default -> throw new IllegalArgumentException(mode);
        };
        FakeClient client = new FakeClient(failure, mode.equals("body-closed"), version);
        QuackHttpTransport transport = new QuackHttpTransport(ENDPOINT, client, Duration.ofSeconds(30));
        transport.setProtocolVersion(version);
        check(transport.endpointCandidates().length == 2, "The isolated fixture must resolve two IPv4 addresses");
        boolean control = kind.equals("cancel") || kind.equals("disconnect");
        boolean safeFallback = (mode.equals("connect") || mode.equals("connect-timeout")) && !control;
        QuackMessage request = request(kind);
        if (safeFallback) {
            check(transport.send(request) instanceof QuackMessage.SuccessResponse, "Expected successful fallback");
            check(client.requests.size() == 2, "Genuine connect failure must try the second address");
            check(!client.requests.get(0).uri().equals(client.requests.get(1).uri()), "Fallback must change address");
            check(client.accepted == 1, "Only the successful connection may accept a request");
        } else {
            try {
                transport.send(request);
                throw new AssertionError("Uncertain request replayed: sends=" + client.requests.size()
                        + ", simulated accepts=" + client.accepted);
            } catch (QuackException error) {
                check(error.getCause() == failure, "Preserve the original failure");
            }
            check(client.requests.size() == 1, "No address replay after an uncertain failure or control request");
            check(client.accepted <= 1, "An accepted write/FETCH must not be performed twice");
        }
        check(client.requests.get(0).timeout().orElseThrow().equals(Duration.ofSeconds(control ? 2 : 30)),
                "Preserve control and ordinary request timeout behavior");
        if (mode.equals("body-closed") || safeFallback) check(client.bodyClosed, "Response body must close");
        System.out.println("PASS " + mode + "/v" + version + "/" + kind + ": sends=" + client.requests.size()
                + ", simulated accepts=" + client.accepted);
    }

    private static QuackMessage request(String kind) {
        HugeIntParts uuid = new HugeIntParts(0, 7);
        LogicalType integer = LogicalType.of(LogicalTypeId.INTEGER);
        DataChunk chunk = new DataChunk(1, List.of(integer),
                List.of(new DecodedVector.IntVec(integer, new int[]{42}, null)));
        return switch (kind) {
            case "prepare" -> new QuackMessage.PrepareRequest(MessageHeader.of(MessageType.PREPARE_REQUEST),
                    "INSERT INTO replay_example VALUES (1)", uuid, Optional.of(1L));
            case "fetch" -> new QuackMessage.FetchRequest(MessageHeader.of(MessageType.FETCH_REQUEST), uuid, 1, 0);
            case "append" -> new QuackMessage.AppendRequest(MessageHeader.of(MessageType.APPEND_REQUEST),
                    Optional.of("main"), "replay_example", chunk);
            case "send-data" -> new QuackMessage.SendDataRequest(MessageHeader.of(MessageType.SEND_DATA_REQUEST),
                    "stream", List.of(chunk), Optional.of(1L), Optional.empty());
            case "heartbeat" -> new QuackMessage.HeartbeatRequest(MessageHeader.of(MessageType.HEARTBEAT_REQUEST));
            case "cancel" -> new QuackMessage.CancelRequest(MessageHeader.of(MessageType.CANCEL_REQUEST), uuid);
            case "disconnect" -> new QuackMessage.DisconnectMessage(MessageHeader.of(MessageType.DISCONNECT_MESSAGE));
            default -> throw new IllegalArgumentException(kind);
        };
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class FakeClient extends HttpClient {
        private final IOException failure;
        private final boolean failBody;
        private final byte[] success;
        private final List<HttpRequest> requests = new ArrayList<>();
        private int accepted;
        private boolean bodyClosed;

        FakeClient(IOException failure, boolean failBody, int version) {
            this.failure = failure;
            this.failBody = failBody;
            success = MessageCodec.encode(new QuackMessage.SuccessResponse(MessageHeader.of(MessageType.SUCCESS_RESPONSE)), version);
        }

        @Override @SuppressWarnings("unchecked")
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) throws IOException {
            requests.add(request);
            if (requests.size() == 1 && !failBody) {
                // Connect failures prove no request was accepted; other failures
                // can occur after an operation advanced or a write took effect.
                if (!(failure instanceof ConnectException) && !(failure instanceof HttpConnectTimeoutException)) accepted++;
                throw failure;
            }
            accepted++;
            InputStream body = new ByteArrayInputStream(success) {
                @Override public void close() { bodyClosed = true; }
            };
            if (failBody) body = new InputStream() {
                @Override public int read() throws IOException { throw failure; }
                @Override public void close() { bodyClosed = true; }
            };
            return new Reply<>(request, (T) body);
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
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                HttpResponse.BodyHandler<T> handler) { throw new UnsupportedOperationException(); }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                HttpResponse.BodyHandler<T> handler, HttpResponse.PushPromiseHandler<T> pushHandler) {
            throw new UnsupportedOperationException();
        }
    }

    private record Reply<T>(HttpRequest request, T body) implements HttpResponse<T> {
        @Override public int statusCode() { return 200; }
        @Override public HttpHeaders headers() { return HttpHeaders.of(Map.of(), (k, v) -> true); }
        @Override public Optional<HttpResponse<T>> previousResponse() { return Optional.empty(); }
        @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
        @Override public URI uri() { return request.uri(); }
        @Override public HttpClient.Version version() { return HttpClient.Version.HTTP_1_1; }
    }
}

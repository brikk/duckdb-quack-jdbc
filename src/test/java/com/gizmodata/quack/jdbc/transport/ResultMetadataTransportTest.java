package com.gizmodata.quack.jdbc.transport;

import com.gizmodata.quack.jdbc.QuackException;
import com.gizmodata.quack.jdbc.QuackProtocolException;
import com.gizmodata.quack.jdbc.codec.HugeIntParts;
import com.gizmodata.quack.jdbc.message.*;
import com.gizmodata.quack.jdbc.sql.QuackDriver;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.sql.SQLException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

class ResultMetadataTransportTest {
    private static final String CAP = "X-Quack-Result-Metadata";
    private static final String KIND = "X-Quack-Result-Kind";
    private static final QuackMessage.ConnectionResponse CONNECTION = new QuackMessage.ConnectionResponse(
            MessageHeader.of(MessageType.CONNECTION_RESPONSE).withConnectionId("metadata-test"),
            Optional.of("v1.5.5"), Optional.empty(), Optional.of(1L));
    private static final QuackMessage.PrepareResponse PREPARE = new QuackMessage.PrepareResponse(
            MessageHeader.of(MessageType.PREPARE_RESPONSE), List.of(), List.of(), false,
            List.of(), new HugeIntParts(0, 0));

    @Test
    void annotationsNeverChangeV1BinaryBodies() {
        var annotatedConnection = new QuackMessage.ConnectionResponse(CONNECTION.header(),
                CONNECTION.serverDuckdbVersion(), CONNECTION.serverPlatform(), CONNECTION.quackVersion(), Optional.of(1L));
        assertArrayEquals(MessageCodec.encode(CONNECTION), MessageCodec.encode(annotatedConnection));
        assertEquals(CONNECTION, MessageCodec.decode(MessageCodec.encode(annotatedConnection)));
        for (var kind : QuackMessage.ResultKind.values()) {
            var annotated = new QuackMessage.PrepareResponse(PREPARE.header(), PREPARE.resultTypes(),
                    PREPARE.resultNames(), false, PREPARE.results(), PREPARE.resultUuid(), Optional.of(kind));
            assertArrayEquals(MessageCodec.encode(PREPARE), MessageCodec.encode(annotated));
            assertEquals(PREPARE, MessageCodec.decode(MessageCodec.encode(annotated)));
        }
    }

    @Test
    void parsesAllKindsAndCapabilityWithoutChangingBinaryDecode() throws Exception {
        try (var server = new Server()) {
            server.response = CONNECTION;
            server.headers = Map.of(CAP, List.of("1"));
            assertEquals(Optional.of(1L), ((QuackMessage.ConnectionResponse) server.send()).resultMetadataVersion());
            server.response = PREPARE;
            String[] names = {"query", "changed_rows", "nothing"};
            for (int i = 0; i < names.length; i++) {
                server.headers = Map.of(CAP, List.of("1"), KIND, List.of(names[i]));
                assertEquals(Optional.of(QuackMessage.ResultKind.values()[i]),
                        ((QuackMessage.PrepareResponse) server.send()).resultKind());
            }
            server.headers = Map.of();
            assertEquals(PREPARE, server.send()); // Absence is enforced by the session, not binary decoding.
        }
    }

    @Test
    void rejectsMalformedDuplicateAndInconsistentHeadersEvenInLegacy() throws Exception {
        try (var server = new Server()) {
            server.response = PREPARE;
            List<Map<String, List<String>>> invalid = List.of(
                    Map.of(CAP, List.of("secret"), KIND, List.of("query")),
                    Map.of(CAP, List.of("1", "1"), KIND, List.of("query")),
                    Map.of(CAP, List.of("1, 1"), KIND, List.of("query")),
                    Map.of(CAP, List.of("01"), KIND, List.of("query")),
                    Map.of(CAP, List.of(""), KIND, List.of("query")),
                    Map.of(CAP, List.of("1")),
                    Map.of(KIND, List.of("query")),
                    Map.of(CAP, List.of("1"), KIND, List.of("query", "query")),
                    Map.of(CAP, List.of("1"), KIND, List.of("query, query")),
                    Map.of(CAP, List.of("1"), KIND, List.of("QUERY")),
                    Map.of(CAP, List.of("1"), KIND, List.of("secret")));
            for (boolean legacy : new boolean[]{false, true}) {
                server.legacy = legacy;
                for (var headers : invalid) {
                    server.headers = headers;
                    var error = assertThrows(QuackProtocolException.class, server::send);
                    assertFalse(error.getMessage().contains("secret"));
                    assertNull(error.getCause());
                }
            }
            server.response = CONNECTION;
            server.headers = Map.of(CAP, List.of("1"), KIND, List.of("query"));
            assertThrows(QuackProtocolException.class, server::send);
            server.response = new QuackMessage.SuccessResponse(MessageHeader.of(MessageType.SUCCESS_RESPONSE));
            server.headers = Map.of(CAP, List.of("1"));
            assertThrows(QuackProtocolException.class, server::send);
        }
    }

    @Test
    void requestHeadersAreFixedAndLegacyDoesNotNegotiate() throws Exception {
        try (var server = new Server()) {
            var transport = new QuackHttpTransport(URI.create(server.endpoint()), HttpClient.newHttpClient(),
                    Duration.ofSeconds(5), Map.of(CAP.toLowerCase(Locale.ROOT), "secret", KIND, "secret",
                    "Content-Type", "secret", "Accept", "secret"));
            transport.send(new QuackMessage.DisconnectMessage(MessageHeader.of(MessageType.DISCONNECT_MESSAGE)));
            assertEquals(List.of("1"), server.seen.get().get(CAP));
            assertEquals(List.of("application/duckdb"), server.seen.get().get("Content-Type"));
            assertEquals(List.of("application/duckdb"), server.seen.get().get("Accept"));
            assertNull(server.seen.get().get(KIND));
            server.legacy = true;
            server.send();
            assertNull(server.seen.get().get(CAP));
        }
    }

    @Test
    void propertyValidationIsExactAndDiagnosticsDoNotLeakValues() {
        assertTrue(QuackUri.parse("jdbc:quack://example.test").requiresResultMetadata());
        assertFalse(QuackUri.parse("jdbc:quack://example.test?resultMetadata=legacy").requiresResultMetadata());
        for (String value : List.of("", "Required", "LEGACY", " legacy", "secret", "1")) {
            Properties properties = new Properties();
            properties.setProperty("resultMetadata", value);
            var error = assertThrows(QuackException.class,
                    () -> QuackUri.parse("jdbc:quack://example.test", properties));
            assertEquals("Quack JDBC property resultMetadata must be required or legacy", error.getMessage());
            assertNull(error.getCause());
        }
        for (String name : List.of(CAP, KIND, CAP.toLowerCase(Locale.ROOT), KIND.toUpperCase(Locale.ROOT))) {
            Properties properties = new Properties();
            properties.setProperty("httpHeader." + name, "secret");
            assertThrows(QuackException.class, () -> QuackUri.parse("jdbc:quack://example.test", properties));
        }
    }

    @Test
    void httpHandshakeRejectionNeverSendsSqlAndDisconnectsDecodedConnection() throws Exception {
        for (String capability : List.of("missing", "secret", "1")) {
            List<MessageType> requests = new CopyOnWriteArrayList<>();
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/quack", exchange -> {
                QuackMessage request = MessageCodec.decode(exchange.getRequestBody().readAllBytes());
                requests.add(request.header().type());
                QuackMessage response = new QuackMessage.SuccessResponse(MessageHeader.of(MessageType.SUCCESS_RESPONSE));
                if (request instanceof QuackMessage.ConnectionRequest) {
                    response = CONNECTION;
                    if (!capability.equals("missing")) exchange.getResponseHeaders().add(CAP, capability);
                }
                if (request instanceof QuackMessage.DisconnectMessage) {
                    assertEquals(CONNECTION.header().connectionId(), request.header().connectionId());
                }
                byte[] bytes = MessageCodec.encode(response);
                exchange.sendResponseHeaders(200, bytes.length);
                try (var body = exchange.getResponseBody()) { body.write(bytes); }
            });
            server.start();
            try {
                String url = "jdbc:quack://127.0.0.1:" + server.getAddress().getPort();
                if (capability.equals("1")) {
                    try (var ignored = new QuackDriver().connect(url, new Properties())) { }
                } else {
                    var error = assertThrows(SQLException.class,
                            () -> new QuackDriver().connect(url + "/must_not_select", new Properties()));
                    assertFalse(error.getMessage().contains("secret"));
                }
                assertEquals(List.of(MessageType.CONNECTION_REQUEST, MessageType.DISCONNECT_MESSAGE), requests);
            } finally {
                server.stop(0);
            }
        }
    }

    private static final class Server implements AutoCloseable {
        final HttpServer server;
        final AtomicReference<com.sun.net.httpserver.Headers> seen = new AtomicReference<>();
        volatile QuackMessage response = new QuackMessage.SuccessResponse(MessageHeader.of(MessageType.SUCCESS_RESPONSE));
        volatile Map<String, List<String>> headers = Map.of();
        boolean legacy;

        Server() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/quack", exchange -> {
                exchange.getRequestBody().readAllBytes();
                seen.set(exchange.getRequestHeaders());
                headers.forEach((name, values) -> values.forEach(value -> exchange.getResponseHeaders().add(name, value)));
                byte[] bytes = MessageCodec.encode(response);
                exchange.sendResponseHeaders(200, bytes.length);
                try (var body = exchange.getResponseBody()) { body.write(bytes); }
            });
            server.start();
        }

        String endpoint() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/quack"; }
        QuackMessage send() {
            return QuackHttpTransport.from(QuackUri.parse("jdbc:quack://127.0.0.1:" + server.getAddress().getPort()
                    + (legacy ? "?resultMetadata=legacy" : ""))).send(
                    new QuackMessage.DisconnectMessage(MessageHeader.of(MessageType.DISCONNECT_MESSAGE)));
        }
        @Override public void close() { server.stop(0); }
    }
}

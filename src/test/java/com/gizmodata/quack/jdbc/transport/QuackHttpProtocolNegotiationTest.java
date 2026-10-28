package com.gizmodata.quack.jdbc.transport;

import com.gizmodata.quack.jdbc.QuackServerException;
import com.gizmodata.quack.jdbc.codec.DecodeLimits;
import com.gizmodata.quack.jdbc.codec.HugeIntParts;
import com.gizmodata.quack.jdbc.message.DataChunk;
import com.gizmodata.quack.jdbc.message.DecodedVector;
import com.gizmodata.quack.jdbc.message.MessageCodec;
import com.gizmodata.quack.jdbc.message.MessageHeader;
import com.gizmodata.quack.jdbc.message.MessageType;
import com.gizmodata.quack.jdbc.message.QuackMessage;
import com.gizmodata.quack.jdbc.sql.QuackSession;
import com.gizmodata.quack.jdbc.sql.QuackConnection;
import com.gizmodata.quack.jdbc.type.LogicalType;
import com.gizmodata.quack.jdbc.type.LogicalTypeId;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class QuackHttpProtocolNegotiationTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void onlyExplicitServerInvalidationStopsTheHttpSession(boolean mustInvalidate) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger requests = new AtomicInteger();
        AtomicInteger prepares = new AtomicInteger();
        AtomicReference<Throwable> handlerFailure = new AtomicReference<>();
        server.createContext("/quack", exchange -> {
            try {
                int n = requests.incrementAndGet();
                QuackMessage request = MessageCodec.decode(exchange.getRequestBody().readAllBytes(),
                        DecodeLimits.DEFAULT, n == 1 ? 1 : 3);
                QuackMessage response;
                if (request instanceof QuackMessage.ConnectionRequest) {
                    response = new QuackMessage.ConnectionResponse(
                            MessageHeader.of(MessageType.CONNECTION_RESPONSE).withConnectionId("conn"),
                            Optional.of("v2.0.0"), Optional.empty(), Optional.of(3L), Optional.of(60L));
                } else if (request instanceof QuackMessage.PrepareRequest prepare) {
                    if (prepares.incrementAndGet() == 1) {
                        response = new QuackMessage.ErrorResponse(MessageHeader.of(MessageType.ERROR_RESPONSE),
                                "checkpoint failed", "IO", Map.of("detail", "server database"), mustInvalidate);
                    } else {
                        LogicalType type = LogicalType.of(LogicalTypeId.INTEGER);
                        DataChunk chunk = new DataChunk(1, List.of(type),
                                List.of(new DecodedVector.IntVec(type, new int[]{1}, null)));
                        response = new QuackMessage.PrepareResponse(MessageHeader.of(MessageType.PREPARE_RESPONSE),
                                List.of(type), List.of("value"), false, List.of(chunk), prepare.queryUuid());
                    }
                } else {
                    assertInstanceOf(QuackMessage.DisconnectMessage.class, request);
                    response = new QuackMessage.SuccessResponse(MessageHeader.of(MessageType.SUCCESS_RESPONSE));
                }
                byte[] wire = MessageCodec.encode(response, 3);
                exchange.sendResponseHeaders(200, wire.length);
                try (var output = exchange.getResponseBody()) { output.write(wire); }
            } catch (Throwable t) {
                handlerFailure.set(t);
                exchange.sendResponseHeaders(500, -1);
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            QuackUri uri = QuackUri.parse("jdbc:quack://127.0.0.1:" + server.getAddress().getPort());
            try (var connection = new QuackConnection(uri);
                 var statement = connection.createStatement()) {
                SQLException error = assertThrows(SQLException.class, () -> statement.execute("SELECT broken"));
                var cause = assertInstanceOf(QuackServerException.class, error.getCause());
                assertEquals("checkpoint failed", cause.getServerMessage());
                assertEquals("IO", cause.getExceptionType());
                assertEquals(Map.of("detail", "server database"), cause.getExtraInfo());
                assertEquals(mustInvalidate, cause.mustInvalidate());
                assertEquals(mustInvalidate, connection.session().isInvalidated());
                assertEquals(!mustInvalidate, connection.isValid(1));
                if (mustInvalidate) {
                    assertThrows(SQLException.class, () -> statement.execute("SELECT 1"));
                } else {
                    assertTrue(statement.execute("SELECT 1"));
                }
            }
            assertNull(handlerFailure.get(), "HTTP mock handler failure");
            assertEquals(mustInvalidate ? 2 : 5, requests.get(),
                    "invalidated sessions must not probe, retry, reconnect or disconnect");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void selectsV3OverHttpAndDecodesRawFetchBody() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger requests = new AtomicInteger();
        AtomicReference<Throwable> handlerFailure = new AtomicReference<>();
        LogicalType type = LogicalType.of(LogicalTypeId.VARCHAR);
        DataChunk chunk = new DataChunk(1, List.of(type),
                List.of(new DecodedVector.ObjectVec(type, new Object[]{"v3 wire"})));
        server.createContext("/quack", exchange -> {
            try {
                int n = requests.incrementAndGet();
                long version = n <= 2 ? 1 : 3;
                QuackMessage request = MessageCodec.decode(exchange.getRequestBody().readAllBytes(),
                        DecodeLimits.DEFAULT, version);
                QuackMessage response;
                if (n == 1) {
                    assertInstanceOf(QuackMessage.ConnectionRequest.class, request);
                    response = new QuackMessage.ErrorResponse(MessageHeader.of(MessageType.ERROR_RESPONSE),
                            "heartbeat_timeout out of range");
                } else if (n == 2) {
                    assertEquals(Optional.of(60L),
                            assertInstanceOf(QuackMessage.ConnectionRequest.class, request)
                                    .heartbeatTimeoutSeconds());
                    response = new QuackMessage.ConnectionResponse(
                            MessageHeader.of(MessageType.CONNECTION_RESPONSE).withConnectionId("conn"),
                            Optional.of("v2.0.0-dev"), Optional.empty(), Optional.of(3L), Optional.of(60L));
                } else if (n == 3) {
                    var prepare = assertInstanceOf(QuackMessage.PrepareRequest.class, request);
                    response = new QuackMessage.PrepareResponse(MessageHeader.of(MessageType.PREPARE_RESPONSE),
                            List.of(type), List.of("value"), true, List.of(), prepare.queryUuid());
                } else if (n == 4) {
                    assertEquals(1, assertInstanceOf(QuackMessage.FetchRequest.class, request).batchIndex());
                    response = new QuackMessage.FetchResponse(MessageHeader.of(MessageType.FETCH_RESPONSE),
                            List.of(chunk), Optional.of(1L), Optional.empty());
                } else if (n == 5) {
                    assertEquals(2, assertInstanceOf(QuackMessage.FetchRequest.class, request).batchIndex());
                    response = new QuackMessage.FetchResponse(MessageHeader.of(MessageType.FETCH_RESPONSE),
                            List.of(), Optional.empty(), Optional.of(1L));
                } else {
                    assertInstanceOf(QuackMessage.DisconnectMessage.class, request);
                    response = new QuackMessage.SuccessResponse(MessageHeader.of(MessageType.SUCCESS_RESPONSE));
                }
                byte[] wire = MessageCodec.encode(response, version == 1 && n == 1 ? 3 : version);
                exchange.sendResponseHeaders(200, wire.length);
                try (var output = exchange.getResponseBody()) { output.write(wire); }
            } catch (Throwable t) {
                handlerFailure.set(t);
                exchange.sendResponseHeaders(500, -1);
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            QuackUri uri = QuackUri.parse("jdbc:quack://127.0.0.1:" + server.getAddress().getPort());
            try (QuackSession session = QuackSession.connect(uri, QuackHttpTransport.from(uri));
                 QuackSession.Cursor cursor = session.cursor("SELECT 'v3 wire' AS value")) {
                assertEquals(3, session.protocolVersion());
                assertEquals("v3 wire", cursor.nextChunk().columns().get(0).getObject(0));
                assertNull(cursor.nextChunk());
            }
            assertNull(handlerFailure.get(), "HTTP mock handler failure");
            assertEquals(6, requests.get());
        } finally {
            server.stop(0);
        }
    }
}

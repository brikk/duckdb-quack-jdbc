package com.gizmodata.quack.jdbc.sql;

import com.gizmodata.quack.jdbc.codec.DecodeLimits;
import com.gizmodata.quack.jdbc.message.MessageCodec;
import com.gizmodata.quack.jdbc.message.MessageHeader;
import com.gizmodata.quack.jdbc.message.MessageType;
import com.gizmodata.quack.jdbc.message.QuackMessage;
import com.gizmodata.quack.jdbc.transport.QuackUri;
import com.gizmodata.quack.jdbc.transport.QuackTransport;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(20)
class QuackDisconnectTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void blackholedDisconnectHeadersOrBodyDoNotUseTheThirtySecondQueryTimeout(boolean sendHeaders) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var executor = Executors.newCachedThreadPool();
        server.setExecutor(executor);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger disconnects = new AtomicInteger();
        AtomicReference<Throwable> handlerFailure = new AtomicReference<>();
        server.createContext("/quack", exchange -> {
            try (exchange) {
                var request = MessageCodec.decode(exchange.getRequestBody().readAllBytes(), DecodeLimits.DEFAULT, 3);
                QuackMessage response;
                if (request instanceof QuackMessage.ConnectionRequest) {
                    response = new QuackMessage.ConnectionResponse(MessageHeader.of(MessageType.CONNECTION_RESPONSE)
                            .withConnectionId("test"), Optional.empty(), Optional.empty(), Optional.of(3L), Optional.of(60L));
                } else if (request instanceof QuackMessage.PrepareRequest prepare) {
                    // A legitimate query longer than the teardown deadline must still succeed.
                    Thread.sleep(2200);
                    response = new QuackMessage.PrepareResponse(MessageHeader.of(MessageType.PREPARE_RESPONSE),
                            List.of(), List.of(), false, List.of(), prepare.queryUuid());
                } else if (request instanceof QuackMessage.DisconnectMessage) {
                    disconnects.incrementAndGet();
                    if (sendHeaders) {
                        exchange.sendResponseHeaders(200, 100);
                        exchange.getResponseBody().write(1);
                        exchange.getResponseBody().flush();
                    }
                    release.await(10, TimeUnit.SECONDS);
                    return;
                } else {
                    throw new AssertionError("Unexpected request " + request);
                }
                byte[] wire = MessageCodec.encode(response, 3);
                exchange.sendResponseHeaders(200, wire.length);
                exchange.getResponseBody().write(wire);
            } catch (Throwable e) {
                handlerFailure.set(e);
            }
        });
        server.start();
        try {
            var uri = QuackUri.parse("jdbc:quack://127.0.0.1:" + server.getAddress().getPort() + "?requestTimeout=30");
            QuackSession session = QuackSession.connect(uri);
            try {
                try (var cursor = session.cursor("SELECT slow")) { assertNotNull(cursor); }
                long start = System.nanoTime();
                session.close();
                assertTrue(Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofSeconds(3)) < 0);
                assertTrue(session.isClosed());
                session.close();
                session.sendHeartbeat();
                assertEquals(1, disconnects.get(), "no reconnect or teardown replay");
                assertNull(handlerFailure.get());
            } finally {
                release.countDown();
                session.close();
            }
        } finally {
            release.countDown();
            server.stop(0);
            executor.shutdownNow();
        }
    }

    @Test
    void aCustomTransportIsInterruptedAtTheSameTeardownDeadline() throws Exception {
        CountDownLatch stopped = new CountDownLatch(1);
        AtomicInteger disconnects = new AtomicInteger();
        try (var session = QuackSession.connect(QuackUri.parse("jdbc:quack://example.test"), (QuackTransport) request -> {
            if (request instanceof QuackMessage.ConnectionRequest) {
                return new QuackMessage.ConnectionResponse(MessageHeader.of(MessageType.CONNECTION_RESPONSE)
                        .withConnectionId("test"), Optional.empty(), Optional.empty(), Optional.of(1L));
            }
            disconnects.incrementAndGet();
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); stopped.countDown(); }
            return new QuackMessage.SuccessResponse(MessageHeader.of(MessageType.SUCCESS_RESPONSE));
        })) {
            long start = System.nanoTime();
            session.close();
            assertTrue(Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofSeconds(3)) < 0);
            assertTrue(stopped.await(1, TimeUnit.SECONDS));
            session.close();
            assertEquals(1, disconnects.get());
        }
    }
}

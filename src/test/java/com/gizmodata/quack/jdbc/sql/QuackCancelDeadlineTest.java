package com.gizmodata.quack.jdbc.sql;

import com.gizmodata.quack.jdbc.codec.DecodeLimits;
import com.gizmodata.quack.jdbc.codec.HugeIntParts;
import com.gizmodata.quack.jdbc.message.DataChunk;
import com.gizmodata.quack.jdbc.message.DecodedVector;
import com.gizmodata.quack.jdbc.message.MessageCodec;
import com.gizmodata.quack.jdbc.message.MessageHeader;
import com.gizmodata.quack.jdbc.message.MessageType;
import com.gizmodata.quack.jdbc.message.QuackMessage;
import com.gizmodata.quack.jdbc.transport.QuackTransport;
import com.gizmodata.quack.jdbc.transport.QuackUri;
import com.gizmodata.quack.jdbc.type.LogicalType;
import com.gizmodata.quack.jdbc.type.LogicalTypeId;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class QuackCancelDeadlineTest {
    private static final LogicalType INTEGER = LogicalType.of(LogicalTypeId.INTEGER);
    private static final DataChunk ROW = new DataChunk(1, List.of(INTEGER),
            List.of(new DecodedVector.IntVec(INTEGER, new int[]{1}, null)));

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void lostCancelHeadersOrBodyAndLostDisconnectFailClosedWithinFiveSeconds(boolean partialBody) throws Exception {
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ExecutorService handlers = Executors.newCachedThreadPool();
        ExecutorService caller = Executors.newSingleThreadExecutor();
        CountDownLatch restore = new CountDownLatch(1);
        AtomicInteger cancels = new AtomicInteger();
        AtomicInteger disconnects = new AtomicInteger();
        AtomicInteger prepares = new AtomicInteger();
        AtomicReference<HugeIntParts> queryUuid = new AtomicReference<>();
        AtomicReference<HugeIntParts> cancelUuid = new AtomicReference<>();
        AtomicReference<Throwable> handlerFailure = new AtomicReference<>();
        http.setExecutor(handlers);
        http.createContext("/quack", exchange -> {
            try (exchange) {
                QuackMessage request = MessageCodec.decode(exchange.getRequestBody().readAllBytes(), DecodeLimits.DEFAULT, 3);
                QuackMessage response;
                if (request instanceof QuackMessage.ConnectionRequest) {
                    response = connected();
                } else if (request instanceof QuackMessage.PrepareRequest prepare) {
                    prepares.incrementAndGet();
                    queryUuid.set(prepare.queryUuid());
                    response = prepared(prepare);
                } else if (request instanceof QuackMessage.CancelRequest cancel) {
                    cancels.incrementAndGet();
                    cancelUuid.set(cancel.queryUuid());
                    if (partialBody) {
                        exchange.sendResponseHeaders(200, 100);
                        exchange.getResponseBody().write(1);
                        exchange.getResponseBody().flush();
                    }
                    restore.await(10, TimeUnit.SECONDS);
                    return;
                } else if (request instanceof QuackMessage.DisconnectMessage) {
                    disconnects.incrementAndGet();
                    restore.await(10, TimeUnit.SECONDS);
                    return;
                } else {
                    throw new AssertionError("Unexpected request " + request);
                }
                byte[] wire = MessageCodec.encode(response, 3);
                exchange.sendResponseHeaders(200, wire.length);
                exchange.getResponseBody().write(wire);
            } catch (Throwable error) { handlerFailure.compareAndSet(null, error); }
        });
        http.start();
        try (var connection = new QuackConnection(QuackUri.parse("jdbc:quack://127.0.0.1:"
                + http.getAddress().getPort() + "?requestTimeout=30")); var statement = connection.createStatement()) {
            statement.execute("SELECT pending");
            long start = System.nanoTime();
            Future<?> cancellation = caller.submit(() -> { statement.cancel(); return null; });
            try {
                assertClosedFailure(cancellation);
                assertTrue(Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofSeconds(5)) < 0);
                assertTrue(connection.isClosed());
                assertTrue(connection.session().isClosed());
                assertFalse(connection.isValid(1));
                assertEquals(queryUuid.get(), cancelUuid.get());
                assertNotEquals(new HugeIntParts(0, 0), cancelUuid.get());
                assertEquals(1, cancels.get());
                assertEquals(1, disconnects.get());
                assertEquals(1, prepares.get(), "no SQL replay");
                connection.session().sendHeartbeat();
                connection.close();
                assertEquals(1, disconnects.get(), "close is idempotent");
                assertNull(handlerFailure.get());
            } finally {
                restore.countDown();
                cancellation.cancel(true);
            }
        } finally {
            restore.countDown();
            caller.shutdownNow();
            http.stop(0);
            handlers.shutdownNow();
        }
    }

    @Test
    void uncooperativeCustomControlIoCannotKeepTheCallerWaitingOrReopenTheSession() throws Exception {
        ExecutorService caller = Executors.newFixedThreadPool(2);
        CountDownLatch restore = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(2);
        CountDownLatch cancelEntered = new CountDownLatch(1);
        AtomicInteger cancels = new AtomicInteger();
        AtomicInteger disconnects = new AtomicInteger();
        AtomicInteger interrupted = new AtomicInteger();
        AtomicInteger prepares = new AtomicInteger();
        QuackTransport transport = request -> {
            if (request instanceof QuackMessage.ConnectionRequest) return connected();
            if (request instanceof QuackMessage.PrepareRequest prepare) {
                prepares.incrementAndGet();
                return prepared(prepare);
            }
            if (request instanceof QuackMessage.CancelRequest) { cancels.incrementAndGet(); cancelEntered.countDown(); }
            else if (request instanceof QuackMessage.DisconnectMessage) disconnects.incrementAndGet();
            else return success();
            try {
                // Deliberately ignore interruption until the test restores the custom backend.
                boolean waiting = true;
                while (waiting) {
                    try { restore.await(); waiting = false; }
                    catch (InterruptedException error) { interrupted.incrementAndGet(); }
                }
                return success();
            } finally { finished.countDown(); }
        };
        try (var connection = new QuackConnection(QuackUri.parse("jdbc:quack://example.test?requestTimeout=30"),
                uri -> transport); var statement = connection.createStatement(); var newer = connection.createStatement()) {
            statement.execute("SELECT pending");
            Future<?> cancellation = caller.submit(() -> { statement.cancel(); return null; });
            Future<?> queuedQuery = null;
            try {
                assertTrue(cancelEntered.await(1, TimeUnit.SECONDS));
                queuedQuery = caller.submit(() -> newer.execute("SELECT must_not_overtake_cancel"));
                assertClosedFailure(cancellation);
                assertTrue(connection.isClosed());
                assertFalse(connection.isValid(1));
                assertEquals(1, cancels.get());
                assertEquals(1, disconnects.get());
                assertTrue(interrupted.get() >= 1, "control workers must be interrupted at their deadlines");
                Future<?> pendingQuery = queuedQuery;
                ExecutionException blocked = assertThrows(ExecutionException.class, () -> pendingQuery.get(1, TimeUnit.SECONDS));
                assertInstanceOf(SQLException.class, blocked.getCause());
                assertEquals(1, prepares.get(), "new PREPARE must fail locally after ambiguous cancellation");
            } finally {
                restore.countDown();
                cancellation.cancel(true);
                if (queuedQuery != null) queuedQuery.cancel(true);
            }
            assertTrue(finished.await(2, TimeUnit.SECONDS));
            assertTrue(connection.isClosed(), "late successful responses must never restore an ambiguous session");
            assertThrows(SQLException.class, () -> statement.execute("SELECT must_not_run"));
        } finally { restore.countDown(); caller.shutdownNow(); }
    }

    private static void assertClosedFailure(Future<?> cancellation) {
        ExecutionException error = assertThrows(ExecutionException.class, () -> cancellation.get(5, TimeUnit.SECONDS));
        SQLException sql = assertInstanceOf(SQLException.class, error.getCause());
        assertTrue(sql.getMessage().contains("could not be confirmed"), sql.getMessage());
        assertTrue(sql.getMessage().contains("session was closed"), sql.getMessage());
    }

    private static QuackMessage.ConnectionResponse connected() {
        return new QuackMessage.ConnectionResponse(MessageHeader.of(MessageType.CONNECTION_RESPONSE)
                .withConnectionId("test"), Optional.empty(), Optional.empty(), Optional.of(3L), Optional.of(60L));
    }

    private static QuackMessage.PrepareResponse prepared(QuackMessage.PrepareRequest request) {
        return new QuackMessage.PrepareResponse(MessageHeader.of(MessageType.PREPARE_RESPONSE),
                List.of(INTEGER), List.of("value"), false, List.of(ROW), request.queryUuid());
    }

    private static QuackMessage.SuccessResponse success() {
        return new QuackMessage.SuccessResponse(MessageHeader.of(MessageType.SUCCESS_RESPONSE));
    }
}

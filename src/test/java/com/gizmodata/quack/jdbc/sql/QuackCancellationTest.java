package com.gizmodata.quack.jdbc.sql;

import com.gizmodata.quack.jdbc.QuackServerException;
import com.gizmodata.quack.jdbc.codec.HugeIntParts;
import com.gizmodata.quack.jdbc.message.DataChunk;
import com.gizmodata.quack.jdbc.message.DecodedVector;
import com.gizmodata.quack.jdbc.message.MessageHeader;
import com.gizmodata.quack.jdbc.message.MessageType;
import com.gizmodata.quack.jdbc.message.QuackMessage;
import com.gizmodata.quack.jdbc.transport.QuackTransport;
import com.gizmodata.quack.jdbc.transport.QuackUri;
import com.gizmodata.quack.jdbc.type.LogicalType;
import com.gizmodata.quack.jdbc.type.LogicalTypeId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(10)
class QuackCancellationTest {
    private static final LogicalType INTEGER = LogicalType.of(LogicalTypeId.INTEGER);
    private static final DataChunk ROWS = new DataChunk(3, List.of(INTEGER),
            List.of(new DecodedVector.IntVec(INTEGER, new int[]{1, 2, 3}, null)));

    @Test
    void cancelCanPassABlockedPrepareAndTargetsItsAlreadyAssignedUuid() throws Exception {
        FakeServer server = new FakeServer();
        server.blockPrepare = true;
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (var connection = server.connect(); var statement = connection.createStatement()) {
            Future<?> execution = worker.submit(() -> statement.execute("SELECT blocked"));
            assertTrue(server.preparing.await(2, TimeUnit.SECONDS));
            statement.cancel();
            assertCancelled(execution);
            assertEquals(server.prepareUuid, server.cancelUuid);
            assertNotEquals(new HugeIntParts(0, 0), server.cancelUuid);
            statement.cancel();
            assertEquals(1, server.cancels.get());
            assertTrue(statement.execute("SELECT recovered"));
            assertFalse(connection.session().isInvalidated());
        } finally {
            server.release.countDown();
            worker.shutdownNow();
        }
    }

    @Test
    void cancelInterruptsFetchAndNeverTurnsItIntoSuccessfulEof() throws Exception {
        FakeServer server = new FakeServer();
        server.blockFetch = true;
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (var connection = server.connect(); var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT streaming")) {
            assertTrue(result.next());
            assertTrue(result.next());
            assertTrue(result.next());
            Future<?> execution = worker.submit(result::next);
            assertTrue(server.fetching.await(2, TimeUnit.SECONDS));
            statement.cancel();
            assertCancelled(execution);
            assertEquals("57014", assertThrows(SQLException.class, result::next).getSQLState());
            assertThrows(SQLException.class, () -> result.getInt(1));
            assertTrue(statement.execute("SELECT recovered"));
        } finally {
            server.release.countDown();
            worker.shutdownNow();
        }
    }

    @Test
    void cancelRejectsRowsAlreadyInTheJdbcChunkAndDoesNotCancelANewerStatement() throws Exception {
        FakeServer server = new FakeServer();
        try (var connection = server.connect(); var old = connection.createStatement();
             var newer = connection.createStatement(); var result = old.executeQuery("SELECT old")) {
            assertTrue(result.next());
            assertTrue(newer.execute("SELECT newer"));
            old.cancel();
            assertEquals(0, server.cancels.get());
            assertEquals("57014", assertThrows(SQLException.class, () -> result.getInt(1)).getSQLState());
            assertEquals("57014", assertThrows(SQLException.class, result::next).getSQLState());
            assertTrue(newer.getResultSet().next());
            assertEquals(1, newer.getResultSet().getInt(1));
        }
    }

    @Test
    void aNewPrepareCannotOvertakeTheCancelCheckAbortWindow() throws Exception {
        FakeServer server = new FakeServer();
        server.blockCancel = true;
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try (var connection = server.connect(); var old = connection.createStatement();
             var newer = connection.createStatement()) {
            old.execute("SELECT old");
            Future<?> cancel = workers.submit(() -> { old.cancel(); return null; });
            assertTrue(server.cancelling.await(2, TimeUnit.SECONDS));
            int prepares = server.prepares.get();
            Future<?> next = workers.submit(() -> newer.execute("SELECT newer"));
            assertThrows(TimeoutException.class, () -> next.get(100, TimeUnit.MILLISECONDS));
            assertEquals(prepares, server.prepares.get());
            server.releaseCancel.countDown();
            cancel.get(2, TimeUnit.SECONDS);
            assertEquals(true, next.get(2, TimeUnit.SECONDS));
            assertEquals(1, server.cancels.get());
        } finally {
            server.releaseCancel.countDown();
            workers.shutdownNow();
        }
    }

    @Test
    void completionAndIdleCancelAreNoOpsAndLegacyServersAreExplicitlyUnsupported() throws Exception {
        FakeServer server = new FakeServer();
        try (var connection = server.connect(); var statement = connection.createStatement()) {
            statement.cancel();
            try (var result = statement.executeQuery("SELECT completed")) {
                while (result.next()) result.getInt(1);
            }
            statement.cancel();
            assertEquals(0, server.cancels.get());
        }
        server.version = 1;
        try (var connection = server.connect(); var statement = connection.createStatement()) {
            assertThrows(SQLFeatureNotSupportedException.class, statement::cancel);
        }
    }

    @Test
    void anUnconfirmedStartupCancelClosesTheSessionInsteadOfGuessingWithAWildcard() throws Exception {
        FakeServer server = new FakeServer();
        server.cancelFailure = new QuackServerException("Attempted to cancel a different query");
        try (var connection = server.connect(); var statement = connection.createStatement()) {
            statement.execute("SELECT old");
            assertTrue(assertThrows(SQLException.class, statement::cancel).getMessage().contains("session was closed"));
            assertTrue(connection.session().isClosed());
            assertTrue(connection.isClosed());
            assertFalse(connection.isValid(1));
            assertEquals(1, server.disconnects.get());
            assertNotEquals(new HugeIntParts(0, 0), server.cancelUuid);
        }
    }

    @Test
    void cancelPreservesMustInvalidateAndDoesNotDisconnectAnInvalidatedServer() throws Exception {
        FakeServer server = new FakeServer();
        server.cancelFailure = new QuackServerException("database gone", "Fatal", Map.of(), true);
        try (var connection = server.connect(); var statement = connection.createStatement()) {
            statement.execute("SELECT old");
            assertThrows(SQLException.class, statement::cancel);
            assertTrue(connection.session().isInvalidated());
            assertFalse(connection.isValid(1));
            assertEquals(0, server.disconnects.get());
        }
    }

    private static void assertCancelled(Future<?> result) {
        ExecutionException failure = assertThrows(ExecutionException.class, () -> result.get(2, TimeUnit.SECONDS));
        SQLException error = assertInstanceOf(SQLException.class, failure.getCause());
        assertEquals("57014", error.getSQLState());
        assertTrue(error.getMessage().contains("Interrupted"));
    }

    private static final class FakeServer implements QuackTransport {
        final CountDownLatch preparing = new CountDownLatch(1);
        final CountDownLatch fetching = new CountDownLatch(1);
        final CountDownLatch cancelling = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final CountDownLatch releaseCancel = new CountDownLatch(1);
        final AtomicInteger cancels = new AtomicInteger();
        final AtomicInteger prepares = new AtomicInteger();
        final AtomicInteger disconnects = new AtomicInteger();
        volatile HugeIntParts prepareUuid;
        volatile HugeIntParts cancelUuid;
        boolean blockPrepare;
        boolean blockFetch;
        boolean blockCancel;
        RuntimeException cancelFailure;
        long version = 3;

        QuackConnection connect() {
            return new QuackConnection(QuackUri.parse("jdbc:quack://example.test"), uri -> this);
        }

        @Override public QuackMessage send(QuackMessage request) {
            if (request instanceof QuackMessage.ConnectionRequest) {
                return new QuackMessage.ConnectionResponse(MessageHeader.of(MessageType.CONNECTION_RESPONSE)
                        .withConnectionId("test"), Optional.empty(), Optional.empty(), Optional.of(version),
                        version == 3 ? Optional.of(60L) : Optional.empty());
            }
            if (request instanceof QuackMessage.PrepareRequest prepare) {
                prepares.incrementAndGet();
                prepareUuid = prepare.queryUuid();
                if (blockPrepare && prepare.sql().contains("blocked")) {
                    preparing.countDown();
                    await(release);
                }
                return new QuackMessage.PrepareResponse(MessageHeader.of(MessageType.PREPARE_RESPONSE),
                        List.of(INTEGER), List.of("value"), prepare.sql().contains("streaming"),
                        List.of(ROWS), prepare.queryUuid());
            }
            if (request instanceof QuackMessage.FetchRequest) {
                if (blockFetch) {
                    fetching.countDown();
                    await(release);
                }
                // A completed FETCH racing cancellation can return clean EOF. The local
                // cancellation token must still turn it into an explicit interruption.
                return new QuackMessage.FetchResponse(MessageHeader.of(MessageType.FETCH_RESPONSE),
                        List.of(), Optional.empty(), Optional.of(0L));
            }
            if (request instanceof QuackMessage.CancelRequest cancel) {
                cancelUuid = cancel.queryUuid();
                cancels.incrementAndGet();
                cancelling.countDown();
                if (blockCancel) await(releaseCancel);
                release.countDown();
                if (cancelFailure != null) throw cancelFailure;
            }
            if (request instanceof QuackMessage.DisconnectMessage) disconnects.incrementAndGet();
            return new QuackMessage.SuccessResponse(MessageHeader.of(MessageType.SUCCESS_RESPONSE));
        }

        private static void await(CountDownLatch latch) {
            try { assertTrue(latch.await(5, TimeUnit.SECONDS)); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
        }
    }
}

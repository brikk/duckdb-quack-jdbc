package com.gizmodata.quack.jdbc.it;

import com.gizmodata.quack.jdbc.codec.DecodeLimits;
import com.gizmodata.quack.jdbc.message.MessageCodec;
import com.gizmodata.quack.jdbc.message.QuackMessage;
import com.gizmodata.quack.jdbc.sql.QuackConnection;
import com.gizmodata.quack.jdbc.sql.QuackResultSet;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIf;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.gizmodata.quack.jdbc.it.QuackIntegrationTest#duckdbAvailable")
@Timeout(30)
public class QuackLifecycleIntegrationTest {
    private QuackServerFixture server;
    @BeforeAll void start() throws Exception { server = QuackServerFixture.tryStart(); assertNotNull(server); }
    @AfterAll void stop() { if (server != null) server.close(); }

    @Test
    void defaultPrefixNeedsFetchAndACutConnectionCannotBecomePartialEof() throws Exception {
        try (var proxy = new Proxy(server); var connection = proxy.connect()) {
            // Non-sequential BIGINTs exercise the default byte-sized batches without
            // compressed sequences or UBIGINT object expansion hiding the boundary.
            try (var cursor = connection.session().cursor(
                    "SELECT (hash(i) % 9223372036854775807)::BIGINT FROM range(5000000) t(i)");
                 var rows = new QuackResultSet(null, cursor)) {
                assertTrue(cursor.materializedRowCount() > 0);
                assertTrue(cursor.materializedRowCount() < 5_000_000,
                        "PREPARE must not buffer the entire result");
                assertTrue(rows.next());
                proxy.cut.set(true);
                long[] seen = {1};
                SQLException failure = assertThrows(SQLException.class, () -> {
                    while (rows.next()) { rows.getLong(1); seen[0]++; }
                });
                assertTrue(failure.getMessage().contains("fetch"), failure.getMessage());
                assertTrue(seen[0] < 5_000_000);
                assertTrue(proxy.fetches.get() > 0);
                assertThrows(SQLException.class, () -> rows.getLong(1));
            } finally {
                proxy.cut.set(false);
            }
            assertNull(proxy.failure.get());
        }
    }

    @Test
    void aCompressedRangeStreamsWhenTheServerBatchBytesAreBounded() throws Exception {
        try (var proxy = new Proxy(server); var connection = proxy.connect(); var statement = connection.createStatement()) {
            if (connection.session().protocolVersion() != 3) return;
            statement.execute("SET quack_target_batch_bytes=16384");
            try (var cursor = connection.session().cursor("SELECT * FROM range(20000000)");
                 var rows = new QuackResultSet(null, cursor)) {
                assertTrue(cursor.materializedRowCount() < 20_000_000);
                assertTrue(rows.next());
                proxy.cut.set(true);
                assertThrows(SQLException.class, () -> { while (rows.next()) rows.getLong(1); });
                assertTrue(proxy.fetches.get() > 0);
            } finally { proxy.cut.set(false); }
            assertNull(proxy.failure.get());
        }
    }

    @Test
    void cancelInterruptsARealBlockedPrepareAndAllowsConnectionReuse() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (var connection = (QuackConnection) DriverManager.getConnection(server.jdbcUrl());
             var statement = connection.createStatement()) {
            if (connection.session().protocolVersion() != 3) {
                assertThrows(SQLFeatureNotSupportedException.class, statement::cancel);
                return;
            }
            String marker = "quack_jdbc_cancel_prepare";
            statement.execute("SET max_execution_time=10000");
            Future<?> execution = worker.submit(() -> statement.executeQuery(
                    "/* " + marker + " */ SELECT sum(i) FROM range(1000000000000) t(i)"));
            awaitActive(marker, execution);
            long start = System.nanoTime();
            statement.cancel();
            assertCancelled(execution);
            assertTrue(Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofSeconds(3)) < 0);
            assertTrue(connection.isValid(1));
        } finally { worker.shutdownNow(); }
    }

    @Test
    void cancelAnInflightFetchEvenWhenItsSuccessfulReplyWasAlreadyProduced() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (var proxy = new Proxy(server); var connection = proxy.connect(); var statement = connection.createStatement()) {
            if (connection.session().protocolVersion() != 3) return;
            statement.execute("SET quack_target_batch_bytes=16384");
            try (var result = statement.executeQuery("SELECT hash(i) FROM range(10000000) t(i)")) {
                proxy.holdFetch.set(true);
                Future<?> execution = worker.submit(() -> { while (result.next()) result.getObject(1); return null; });
                assertTrue(proxy.fetchHeld.await(3, TimeUnit.SECONDS));
                statement.cancel();
                proxy.releaseFetch.countDown();
                assertCancelled(execution);
                assertEquals("57014", assertThrows(SQLException.class, result::next).getSQLState());
            } finally { proxy.releaseFetch.countDown(); }
            proxy.holdFetch.set(false);
            assertTrue(connection.isValid(1));
            assertNull(proxy.failure.get());
        } finally { worker.shutdownNow(); }
    }

    @Test
    void cancelAbortsAProducerWithABoundedFullResultBufferAndLeavesTransactionsCallerManaged() throws Exception {
        try (var connection = (QuackConnection) DriverManager.getConnection(server.jdbcUrl());
             var admin = DriverManager.getConnection(server.jdbcUrl()); var config = admin.createStatement();
             var statement = connection.createStatement()) {
            if (connection.session().protocolVersion() != 3) return;
            config.execute("SET GLOBAL quack_server_stream_buffer_bytes=65536");
            try {
                statement.execute("SET threads=1");
                statement.execute("SET quack_target_batch_bytes=16384");
                statement.execute("CREATE TEMP TABLE cancel_tx (v INTEGER)");
                connection.setAutoCommit(false);
                statement.execute("INSERT INTO cancel_tx VALUES (1)");
                try (var rows = statement.executeQuery(
                        "/* quack_jdbc_buffer_cancel */ SELECT hash(i) FROM range(1000000000000) t(i)")) {
                    assertTrue(rows.next());
                    // No FETCH: an enormous producer can fill only the configured 64 KiB.
                    // Allow its worker to reach the capacity wait before testing AbortStatement.
                    Thread.sleep(150);
                    long start = System.nanoTime();
                    statement.cancel();
                    assertTrue(Duration.ofNanos(System.nanoTime() - start).compareTo(Duration.ofSeconds(3)) < 0);
                    assertEquals("57014", assertThrows(SQLException.class, rows::next).getSQLState());
                }
                // Cancellation must not silently commit/reconnect. The caller can roll back
                // whether DuckDB kept the transaction active or marked it aborted.
                connection.rollback();
                connection.setAutoCommit(true);
                try (var rows = statement.executeQuery("SELECT count(*) FROM cancel_tx")) {
                    assertTrue(rows.next());
                    assertEquals(0, rows.getLong(1));
                }
                assertTrue(connection.isValid(1));
            } finally { config.execute("RESET GLOBAL quack_server_stream_buffer_bytes"); }
        }
    }

    private void awaitActive(String marker, Future<?> execution) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        try (var admin = DriverManager.getConnection(server.jdbcUrl()); var statement = admin.createStatement()) {
            while (System.nanoTime() < deadline) {
                assertFalse(execution.isDone(), "query finished before cancellation");
                try (var rows = statement.executeQuery("SELECT * FROM quack_active_connections()")) {
                    while (rows.next()) {
                        for (int column = 1; column <= rows.getMetaData().getColumnCount(); column++) {
                            String value = rows.getString(column);
                            if (value != null && value.contains(marker)) return;
                        }
                    }
                }
                Thread.sleep(10);
            }
        }
        fail("server did not expose the active query before the cancellation deadline");
    }

    private static void assertCancelled(Future<?> execution) {
        ExecutionException failure = assertThrows(ExecutionException.class, () -> execution.get(2, TimeUnit.SECONDS));
        SQLException sql = assertInstanceOf(SQLException.class, failure.getCause());
        assertEquals("57014", sql.getSQLState());
        assertTrue(sql.getMessage().contains("Interrupted"), sql.getMessage());
    }

    /** Real HTTP fault injection, with concurrent forwarding so CANCEL is not serialized behind FETCH. */
    private static final class Proxy implements AutoCloseable {
        final HttpServer http;
        final ExecutorService workers = Executors.newCachedThreadPool();
        final AtomicBoolean cut = new AtomicBoolean();
        final AtomicBoolean holdFetch = new AtomicBoolean();
        final CountDownLatch fetchHeld = new CountDownLatch(1);
        final CountDownLatch releaseFetch = new CountDownLatch(1);
        final AtomicInteger fetches = new AtomicInteger();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final QuackServerFixture backend;
        volatile long version = 1;

        Proxy(QuackServerFixture backend) throws Exception {
            this.backend = backend;
            http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            http.setExecutor(workers);
            HttpClient client = HttpClient.newHttpClient();
            http.createContext("/quack", exchange -> {
                try (exchange) {
                    byte[] bytes = exchange.getRequestBody().readAllBytes();
                    QuackMessage message = MessageCodec.decode(bytes, DecodeLimits.DEFAULT, version);
                    boolean fetch = message instanceof QuackMessage.FetchRequest;
                    if (fetch) fetches.incrementAndGet();
                    if (cut.get()) return; // Close a real HTTP request without a response.
                    var response = client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:"
                                    + backend.port() + "/quack"))
                            .timeout(Duration.ofSeconds(15)).POST(HttpRequest.BodyPublishers.ofByteArray(bytes)).build(),
                            HttpResponse.BodyHandlers.ofByteArray());
                    if (message instanceof QuackMessage.ConnectionRequest && response.statusCode() == 200) {
                        QuackMessage reply = MessageCodec.decode(response.body());
                        if (reply instanceof QuackMessage.ConnectionResponse connected) {
                            version = connected.quackVersion().orElse(1L);
                        }
                    }
                    if (fetch && holdFetch.get()) {
                        fetchHeld.countDown();
                        assertTrue(releaseFetch.await(5, TimeUnit.SECONDS));
                    }
                    exchange.sendResponseHeaders(response.statusCode(), response.body().length);
                    exchange.getResponseBody().write(response.body());
                } catch (Throwable error) { failure.compareAndSet(null, error); }
            });
            http.start();
        }

        QuackConnection connect() throws SQLException {
            return (QuackConnection) DriverManager.getConnection("jdbc:quack://127.0.0.1:" + http.getAddress().getPort()
                    + "?token=" + backend.token() + "&requestTimeout=5");
        }

        @Override public void close() {
            releaseFetch.countDown();
            http.stop(0);
            workers.shutdownNow();
        }
    }
}

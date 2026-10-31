package com.gizmodata.quack.jdbc.sql;

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

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.sql.SQLException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class QuackAffectedRowsTest {
    private static final List<String> LABELS = List.of("Count", "rows_affected", "count_star()", "COUNT_STAR()");

    @Test
    void countLabelsAreCountsOnlyForNonReturningDmlAndSqlIsSentOncePerCall() throws Exception {
        for (String label : LABELS) {
            for (String sql : List.of("INSERT INTO t VALUES(1),(2)", "UPDATE t SET v=1", "DELETE FROM t",
                    "MERGE INTO t USING s ON true WHEN MATCHED THEN DELETE",
                    "WITH update AS (SELECT 1) INSERT INTO t SELECT * FROM update")) {
                ShapeServer server = new ShapeServer(label, LogicalTypeId.BIGINT, new Object[]{2L});
                try (var c = server.connect(); var s = c.createStatement()) {
                    assertFalse(s.execute(sql), label + " " + sql);
                    assertEquals(2, s.getUpdateCount()); assertNull(s.getResultSet());
                    assertEquals(List.of(sql), server.sql);
                    assertEquals(0, server.fetches);
                    assertEquals(2, s.executeUpdate(sql));
                    assertEquals(List.of(sql, sql), server.sql, "one PREPARE per intentional execution, no probes/replay");
                    assertFalse(s.getMoreResults()); assertEquals(-1, s.getUpdateCount());
                }
            }
        }
    }

    @Test
    void countShapedSelectAndReturningResultsKeepTheirRowsAndMetadata() throws Exception {
        for (String label : LABELS) {
            for (String sql : List.of("SELECT count(*) FROM t", "SELECT 2 AS Count",
                    "WITH update AS (SELECT 2) SELECT * FROM update", "CALL p()",
                    "INSERT INTO t VALUES(1) RETURNING id AS Count", "UPDATE t SET v=1 RETURNING v",
                    "DELETE FROM t RETURNING id AS rows_affected", "INSERT INTO t VALUES(1); SELECT 2")) {
                ShapeServer server = new ShapeServer(label, LogicalTypeId.BIGINT, new Object[]{2L});
                try (var c = server.connect(); var s = c.createStatement()) {
                    assertTrue(s.execute(sql), label + " " + sql);
                    assertEquals(-1, s.getUpdateCount()); assertNotNull(s.getResultSet());
                    assertEquals(0, server.fetches);
                    try (var rows = s.getResultSet()) {
                        assertEquals(label, rows.getMetaData().getColumnName(1));
                        assertTrue(rows.next()); assertEquals(2, rows.getLong(1)); assertFalse(rows.next());
                    }
                    assertEquals(List.of(sql), server.sql);
                }
            }
        }
    }

    @Test
    void returningAndSelectResultsRemainLazyEvenWithOneInlineCountShapedRow() throws Exception {
        for (String sql : List.of("SELECT 2 AS Count", "UPDATE t SET v=1 RETURNING v AS Count")) {
            ShapeServer server = new ShapeServer("Count", LogicalTypeId.BIGINT, new Object[]{2L});
            server.more = true;
            try (var c = server.connect(); var s = c.createStatement(); var rows = s.executeQuery(sql)) {
                assertEquals(0, server.fetches);
                assertEquals(-1, s.getUpdateCount());
                assertTrue(rows.next()); assertEquals(2, rows.getLong(1)); assertEquals(0, server.fetches);
                assertTrue(rows.next()); assertEquals(3, rows.getLong(1));
                assertFalse(rows.next()); assertEquals(2, server.fetches);
                assertEquals(List.of(sql), server.sql);
            }
        }
    }

    @Test
    void preparedBindingsContainingReturningDoNotChangeClassification() throws Exception {
        ShapeServer server = new ShapeServer("count_star()", LogicalTypeId.BIGINT, new Object[]{2L});
        try (var c = server.connect(); var p = c.prepareStatement("UPDATE t SET note=? WHERE id=?")) {
            p.setString(1, "RETURNING; 'literal' /* comment */ $$text$$"); p.setInt(2, 1);
            assertEquals(2, p.executeUpdate());
            assertEquals(1, server.sql.size()); assertEquals(0, server.fetches);
        }
    }

    @Test
    void countsMustHaveASingleNonNullNonnegativeIntegralValue() throws Exception {
        for (Object[] values : List.of(new Object[]{null}, new Object[]{-1L}, new Object[]{1L, 2L}, new Object[0])) {
            ShapeServer server = new ShapeServer("count_star()", LogicalTypeId.BIGINT, values);
            try (var c = server.connect(); var s = c.createStatement()) {
                assertTrue(s.execute("INSERT INTO t VALUES(1)")); assertEquals(-1, s.getUpdateCount());
            }
        }
        ShapeServer nonintegral = new ShapeServer("count_star()", LogicalTypeId.DOUBLE, new Object[]{2.0});
        try (var c = nonintegral.connect(); var s = c.createStatement()) {
            assertTrue(s.execute("INSERT INTO t VALUES(1)"));
        }
        ShapeServer multipleChunks = new ShapeServer("count_star()", LogicalTypeId.BIGINT, new Object[]{2L});
        multipleChunks.multipleChunks = true;
        try (var c = multipleChunks.connect(); var s = c.createStatement()) {
            assertTrue(s.execute("INSERT INTO t VALUES(1)"));
            assertEquals(-1, s.getUpdateCount());
            try (var rows = s.getResultSet()) {
                assertTrue(rows.next()); assertEquals(2, rows.getLong(1));
                assertTrue(rows.next()); assertEquals(2, rows.getLong(1)); assertFalse(rows.next());
            }
        }
        for (Object count : List.of(0L, Long.MAX_VALUE, BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE))) {
            LogicalTypeId type = count instanceof BigInteger ? LogicalTypeId.UBIGINT : LogicalTypeId.BIGINT;
            ShapeServer server = new ShapeServer("count_star()", type, new Object[]{count});
            try (var c = server.connect(); var s = c.createStatement()) {
                assertEquals(count.equals(0L) ? 0 : Integer.MAX_VALUE, s.executeUpdate("UPDATE t SET v=1"));
            }
        }
    }

    @Test
    void legacyDdlCountsStillWorkButAggregateLabelsAreNotGeneralCommandTags() throws Exception {
        for (String label : LABELS) {
            ShapeServer server = new ShapeServer(label, LogicalTypeId.BIGINT, new Object[]{2L});
            try (var c = server.connect(); var s = c.createStatement()) {
                boolean legacy = label.equals("Count") || label.equals("rows_affected");
                assertEquals(!legacy, s.execute("CREATE TABLE t AS SELECT 1"));
                assertEquals(legacy ? 2 : -1, s.getUpdateCount());
            }
        }
    }

    @Test
    void cancellationDuringCountClassificationCannotBecomeSuccessfulDml() throws Exception {
        CountDownLatch classifying = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        AtomicInteger reads = new AtomicInteger();
        Number value = new Number() {
            @Override public long longValue() {
                if (reads.incrementAndGet() == 1) {
                    classifying.countDown();
                    try { assertTrue(resume.await(3, TimeUnit.SECONDS)); }
                    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
                }
                return 2;
            }
            @Override public int intValue() { return (int) longValue(); }
            @Override public float floatValue() { return longValue(); }
            @Override public double doubleValue() { return longValue(); }
        };
        ShapeServer server = new ShapeServer("count_star()", LogicalTypeId.BIGINT, new Object[]{value});
        var worker = Executors.newSingleThreadExecutor();
        try (var c = server.connect(); var s = c.createStatement()) {
            var execution = worker.submit(() -> s.execute("UPDATE t SET v=1"));
            assertTrue(classifying.await(2, TimeUnit.SECONDS));
            s.cancel();
            resume.countDown();
            ExecutionException failure = assertThrows(ExecutionException.class, () -> execution.get(2, TimeUnit.SECONDS));
            assertEquals("57014", assertInstanceOf(SQLException.class, failure.getCause()).getSQLState());
            assertEquals(-1, s.getUpdateCount()); assertNull(s.getResultSet());
            assertEquals(1, server.sql.size());
        } finally { resume.countDown(); worker.shutdownNow(); }
    }

    private static final class ShapeServer implements QuackTransport {
        private final String label;
        private final LogicalType type;
        private final Object[] values;
        private final List<String> sql = new ArrayList<>();
        private boolean more;
        private boolean multipleChunks;
        private int fetches;

        private ShapeServer(String label, LogicalTypeId id, Object[] values) {
            this.label = label; this.type = LogicalType.of(id); this.values = values;
        }

        private QuackConnection connect() {
            return new QuackConnection(QuackUri.parse("jdbc:quack://example.test"), uri -> this);
        }

        @Override public QuackMessage send(QuackMessage request) {
            if (request instanceof QuackMessage.ConnectionRequest) {
                return new QuackMessage.ConnectionResponse(MessageHeader.of(MessageType.CONNECTION_RESPONSE)
                        .withConnectionId("counts"), Optional.empty(), Optional.empty(), Optional.of(3L), Optional.of(60L));
            }
            if (request instanceof QuackMessage.PrepareRequest prepare) {
                sql.add(prepare.sql());
                return new QuackMessage.PrepareResponse(MessageHeader.of(MessageType.PREPARE_RESPONSE),
                        List.of(type), List.of(label), more,
                        multipleChunks ? List.of(chunk(values), chunk(values)) : List.of(chunk(values)), prepare.queryUuid());
            }
            if (request instanceof QuackMessage.FetchRequest) {
                fetches++;
                return fetches == 1 ? new QuackMessage.FetchResponse(MessageHeader.of(MessageType.FETCH_RESPONSE),
                        List.of(chunk(new Object[]{3L})), Optional.of(1L), Optional.empty())
                        : new QuackMessage.FetchResponse(MessageHeader.of(MessageType.FETCH_RESPONSE),
                        List.of(), Optional.empty(), Optional.of(1L));
            }
            return new QuackMessage.SuccessResponse(MessageHeader.of(MessageType.SUCCESS_RESPONSE));
        }

        private DataChunk chunk(Object[] values) {
            return new DataChunk(values.length, List.of(type), List.of(new DecodedVector.ObjectVec(type, values)));
        }
    }
}

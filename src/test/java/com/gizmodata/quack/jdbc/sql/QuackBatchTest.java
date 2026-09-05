package com.gizmodata.quack.jdbc.sql;

import org.junit.jupiter.api.Test;

import java.sql.BatchUpdateException;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class QuackBatchTest {
    @Test
    void failureAtEachPositionReportsOnlyCompletedCommandsAndKeepsDiagnostics() throws Exception {
        for (boolean prepared : new boolean[]{false, true}) {
            for (int failAt = 0; failAt < 3; failAt++) {
                Script script = new Script(failAt);
                try (Statement statement = statement(prepared, script)) {
                    for (int i = 0; i < 3; i++) enqueue(statement, i);
                    BatchUpdateException failure = assertThrows(BatchUpdateException.class, statement::executeBatch);
                    assertArrayEquals(Arrays.copyOf(Script.COUNTS, failAt), failure.getUpdateCounts());
                    assertArrayEquals(Arrays.stream(Arrays.copyOf(Script.COUNTS, failAt)).asLongStream().toArray(),
                            failure.getLargeUpdateCounts());
                    assertSame(script.failure, failure.getCause());
                    assertEquals(script.failure.getMessage(), failure.getMessage());
                    assertEquals(script.failure.getSQLState(), failure.getSQLState());
                    assertEquals(script.failure.getErrorCode(), failure.getErrorCode());
                    assertEquals(failAt + 1, script.attempted.size(), "commands after the failure must not execute");
                    assertArrayEquals(new int[0], statement.executeBatch(), "failed batch must be cleared");
                    assertEquals(failAt + 1, script.attempted.size());
                    enqueue(statement, 9);
                    assertArrayEquals(new int[]{Script.COUNTS[(failAt + 1) % 3]}, statement.executeBatch());
                    assertEquals(failAt + 2, script.attempted.size());
                }
            }
        }
    }

    @Test
    void successfulEmptyAndClearedBatchesKeepAccurateCountsAndCheckClosedState() throws Exception {
        for (boolean prepared : new boolean[]{false, true}) {
            Script script = new Script(-1);
            try (Statement statement = statement(prepared, script)) {
                assertArrayEquals(new int[0], statement.executeBatch());
                enqueue(statement, 9);
                statement.clearBatch();
                assertArrayEquals(new int[0], statement.executeBatch());
                assertTrue(script.attempted.isEmpty());
                for (int i = 0; i < 3; i++) enqueue(statement, i);
                assertArrayEquals(Script.COUNTS, statement.executeBatch());
                assertArrayEquals(new int[0], statement.executeBatch());
                assertEquals(3, script.attempted.size());
                statement.close();
                assertThrows(SQLException.class, statement::executeBatch);
            }
        }
    }

    private static Statement statement(boolean prepared, Script script) {
        if (prepared) {
            return new QuackPreparedStatement(null, "UPDATE t SET value = ?") {
                @Override public int executeUpdate(String sql) throws SQLException { return script.execute(sql); }
            };
        }
        return new QuackStatement(null) {
            @Override public int executeUpdate(String sql) throws SQLException { return script.execute(sql); }
        };
    }

    private static void enqueue(Statement statement, int value) throws SQLException {
        if (statement instanceof PreparedStatement prepared) {
            prepared.setInt(1, value);
            prepared.addBatch();
        } else {
            statement.addBatch("UPDATE t SET value = " + value);
        }
    }

    private static final class Script {
        private static final int[] COUNTS = {0, 2, 3};
        private final List<String> attempted = new ArrayList<>();
        private final SQLException failure = new SQLException("simulated duplicate key", "23505", 77);
        private final int failAt;

        private Script(int failAt) { this.failAt = failAt; }

        private int execute(String sql) throws SQLException {
            int index = attempted.size();
            attempted.add(sql);
            if (index == failAt) throw failure;
            return COUNTS[index % COUNTS.length];
        }
    }
}

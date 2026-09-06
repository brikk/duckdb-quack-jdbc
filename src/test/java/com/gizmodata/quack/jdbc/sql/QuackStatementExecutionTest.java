package com.gizmodata.quack.jdbc.sql;

import com.gizmodata.quack.jdbc.QuackException;
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
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.BatchUpdateException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class QuackStatementExecutionTest {
    @Test
    void queryToQueryClosesPreviousResultBeforeSendingSql() throws SQLException {
        var transport = new ScriptedTransport(response(false, 10), response(false, 20));
        try (var connection = connect(transport); var statement = connection.createStatement()) {
            ResultSet previous = statement.executeQuery("SELECT 10 AS value");
            assertTrue(previous.next());
            assertEquals(10, previous.getInt(1));
            transport.beforePrepare = () -> assertDoesNotThrow(() -> assertReset(statement, previous));

            ResultSet current = statement.executeQuery("SELECT 20 AS value");
            assertNotSame(previous, current);
            assertTrue(previous.isClosed());
            assertThrows(SQLException.class, previous::next);
            assertSame(current, statement.getResultSet());
            assertEquals(-1, statement.getUpdateCount());
            assertTrue(current.next());
            assertEquals(20, current.getInt(1));
        }
    }

    @Test
    void queryToUpdateClosesPreviousResultAndUpdateToQueryClearsCount() throws SQLException {
        var transport = new ScriptedTransport(response(false, 10), response(true, 3), response(false, 20));
        try (var connection = connect(transport); var statement = connection.createStatement()) {
            ResultSet previous = statement.executeQuery("SELECT 10 AS value");
            transport.beforePrepare = () -> assertDoesNotThrow(() -> assertReset(statement, previous));
            assertEquals(3, statement.executeUpdate("UPDATE t SET value = 3"));
            assertTrue(previous.isClosed());
            assertNull(statement.getResultSet());
            assertEquals(3, statement.getUpdateCount());

            assertTrue(statement.execute("SELECT 20 AS value"));
            assertEquals(-1, statement.getUpdateCount());
            assertTrue(statement.getResultSet().next());
            assertEquals(20, statement.getResultSet().getInt(1));
        }
    }

    @ParameterizedTest
    @CsvSource({"false, false", "false, true", "true, false", "true, true"})
    void failedQueryOrLazyBeginClearsPreviousResultAndCount(boolean previousUpdate, boolean failBegin)
            throws SQLException {
        var failure = new QuackException("simulated execution failure");
        var transport = new ScriptedTransport(response(previousUpdate, 7), failure);
        try (var connection = connect(transport); var statement = connection.createStatement()) {
            String initialSql = previousUpdate ? "UPDATE t SET value = 7" : "SELECT 7 AS value";
            assertEquals(!previousUpdate, statement.execute(initialSql));
            ResultSet previous = statement.getResultSet();
            assertEquals(previousUpdate ? 7 : -1, statement.getUpdateCount());
            if (previous != null) assertTrue(previous.next());
            if (failBegin) connection.setAutoCommit(false);
            transport.beforePrepare = () -> assertDoesNotThrow(() -> assertReset(statement, previous));

            SQLException error = assertThrows(SQLException.class,
                    () -> statement.executeQuery("SELECT missing_column"));
            assertSame(failure, error.getCause());
            assertReset(statement, previous);
            assertEquals(List.of(initialSql, failBegin ? "BEGIN TRANSACTION" : "SELECT missing_column"),
                    transport.statements);
        }
    }

    @ParameterizedTest
    @CsvSource({"false, false", "false, true", "true, false", "true, true"})
    void everyPreparedEntrypointResetsBeforeBindingValidationOrRendering(
            boolean previousUpdate, boolean missingBinding) throws SQLException {
        var initial = response(previousUpdate, 7);
        var transport = new ScriptedTransport(initial, initial, initial);
        String sql = previousUpdate ? "UPDATE t SET value = ?" : "SELECT ? AS value";
        try (var connection = connect(transport); var statement = connection.prepareStatement(sql)) {
            List<Executable> executions = List.of(statement::execute, statement::executeQuery, statement::executeUpdate);
            for (Executable execution : executions) {
                connection.setAutoCommit(true);
                statement.setInt(1, 7);
                assertEquals(!previousUpdate, statement.execute());
                ResultSet previous = statement.getResultSet();
                assertEquals(previousUpdate ? 7 : -1, statement.getUpdateCount());
                if (previous != null) assertTrue(previous.next());
                connection.setAutoCommit(false);
                var renderFailure = new IllegalStateException("cannot render parameter");
                if (missingBinding) {
                    statement.clearParameters();
                } else {
                    statement.setObject(1, new Object() {
                        @Override public String toString() {
                            assertDoesNotThrow(() -> assertReset(statement, previous));
                            throw renderFailure;
                        }
                    });
                }
                int sent = transport.statements.size();

                if (missingBinding) {
                    assertTrue(assertThrows(SQLException.class, execution).getMessage().contains("not bound"));
                } else {
                    assertSame(renderFailure, assertThrows(IllegalStateException.class, execution));
                }
                assertReset(statement, previous);
                assertEquals(sent, transport.statements.size(), "validation must not send SQL or BEGIN");
            }
        }
    }

    @Test
    void preparedSettersAndBatchChangesKeepCurrentResultAndReexecutionReusesBindings() throws SQLException {
        var transport = new ScriptedTransport(response(false, 1), response(false, 2), response(false, 2));
        try (var connection = connect(transport); var statement = connection.prepareStatement("SELECT ? AS value")) {
            statement.setInt(1, 1);
            ResultSet previous = statement.executeQuery();
            assertTrue(previous.next());
            statement.setInt(1, 2);
            statement.addBatch();
            statement.clearBatch();
            statement.clearParameters();
            assertThrows(SQLException.class, statement::addBatch);
            assertThrows(SQLException.class, () -> statement.setInt(0, 99));
            assertSame(previous, statement.getResultSet());
            assertFalse(previous.isClosed());
            assertEquals(1, previous.getInt(1));

            statement.setInt(1, 2);
            ResultSet current = statement.executeQuery();
            assertTrue(previous.isClosed());
            assertTrue(current.next());
            assertEquals(2, current.getInt(1));
            assertTrue(statement.execute());
            assertTrue(current.isClosed());
            assertEquals(List.of("SELECT /**/1/**/ AS value", "SELECT /**/2/**/ AS value",
                    "SELECT /**/2/**/ AS value"), transport.statements);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preparedBatchRenderingFailureResetsEvenAfterACompletedCommand(boolean completedCommand)
            throws SQLException {
        var initial = response(completedCommand, 7);
        var transport = completedCommand
                ? new ScriptedTransport(initial, response(true, 2), initial)
                : new ScriptedTransport(initial, initial);
        String sql = completedCommand ? "UPDATE t SET value = ?" : "SELECT ? AS value";
        try (var connection = connect(transport); var statement = connection.prepareStatement(sql)) {
            statement.setInt(1, 7);
            statement.execute();
            ResultSet previous = statement.getResultSet();
            if (completedCommand) {
                statement.setInt(1, 2);
                statement.addBatch();
            }
            var failure = new IllegalStateException("cannot render batch parameter");
            statement.setObject(1, new Object() {
                @Override public String toString() {
                    assertDoesNotThrow(() -> assertReset(statement, previous));
                    throw failure;
                }
            });
            statement.addBatch();
            statement.setInt(1, 99);
            statement.addBatch();
            statement.setInt(1, 42);
            assertSame(failure, assertThrows(IllegalStateException.class, statement::executeBatch));
            assertReset(statement, previous);
            assertEquals(completedCommand ? 2 : 1, transport.statements.size());
            if (completedCommand) assertEquals("UPDATE t SET value = /**/2/**/", transport.statements.get(1));

            statement.clearBatch();
            statement.execute();
            assertTrue(transport.statements.get(transport.statements.size() - 1).contains("/**/42/**/"),
                    "batch replay must not replace the current bindings");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedBatchKeepsPrefixCountsAndClearsLastUpdateCount(boolean prepared) throws SQLException {
        var failure = new QuackException("batch command failed");
        var transport = new ScriptedTransport(response(true, 2), failure);
        try (var connection = connect(transport);
             Statement statement = prepared ? connection.prepareStatement("UPDATE t SET value = ?")
                     : connection.createStatement()) {
            for (int i = 1; i <= 3; i++) {
                if (statement instanceof PreparedStatement p) {
                    p.setInt(1, i);
                    p.addBatch();
                } else {
                    statement.addBatch("UPDATE t SET value = " + i);
                }
            }
            BatchUpdateException error = assertThrows(BatchUpdateException.class, statement::executeBatch);
            assertArrayEquals(new int[]{2}, error.getUpdateCounts());
            assertSame(failure, error.getCause().getCause());
            assertReset(statement, null);
            assertEquals(2, transport.statements.size());
            assertArrayEquals(new int[0], statement.executeBatch());
            assertEquals(2, transport.statements.size());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void emptyBatchAlsoClosesPreviousResult(boolean prepared) throws SQLException {
        var transport = new ScriptedTransport(response(false, 7));
        try (var connection = connect(transport);
             Statement statement = prepared ? connection.prepareStatement("SELECT 7 AS value")
                     : connection.createStatement()) {
            ResultSet previous;
            if (statement instanceof PreparedStatement p) {
                previous = p.executeQuery();
                p.addBatch();
            } else {
                previous = statement.executeQuery("SELECT 7 AS value");
                statement.addBatch("SELECT 8 AS value");
            }
            statement.clearBatch();
            assertFalse(previous.isClosed(), "addBatch and clearBatch are not execution");
            assertArrayEquals(new int[0], statement.executeBatch());
            assertReset(statement, previous);
            assertEquals(1, transport.statements.size());
        }
    }

    @Test
    void closeAndGetMoreResultsStillCloseResultsAndTerminateResultDraining() throws SQLException {
        var transport = new ScriptedTransport(response(false, 1), response(true, 3), response(false, 2),
                response(false, 4));
        try (var connection = connect(transport); var statement = connection.createStatement()) {
            ResultSet first = statement.executeQuery("SELECT 1 AS value");
            assertFalse(statement.getMoreResults());
            assertReset(statement, first);
            assertFalse(statement.getMoreResults());
            assertEquals(3, statement.executeUpdate("UPDATE t SET value = 3"));
            assertFalse(statement.getMoreResults());
            assertReset(statement, null);
            ResultSet second = statement.executeQuery("SELECT 2 AS value");
            assertFalse(statement.getMoreResults(Statement.CLOSE_CURRENT_RESULT));
            assertReset(statement, second);
            ResultSet last = statement.executeQuery("SELECT 4 AS value");
            statement.close();
            statement.close();
            assertTrue(statement.isClosed());
            assertTrue(last.isClosed());
            assertThrows(SQLException.class, () -> statement.execute("SELECT 5 AS value"));
            assertEquals(4, transport.statements.size());
        }
    }

    private static void assertReset(Statement statement, ResultSet previous) throws SQLException {
        if (previous != null) {
            assertTrue(previous.isClosed());
            assertThrows(SQLException.class, () -> previous.getInt(1));
        }
        assertNull(statement.getResultSet());
        assertEquals(-1, statement.getUpdateCount());
    }

    private static QuackConnection connect(ScriptedTransport transport) {
        return new QuackConnection(QuackUri.parse("jdbc:quack://example.test?resultMetadata=legacy"), uri -> transport);
    }

    private static QuackMessage.PrepareResponse response(boolean update, long value) {
        LogicalType type = LogicalType.of(LogicalTypeId.BIGINT);
        DataChunk chunk = new DataChunk(1, List.of(type),
                List.of(new DecodedVector.LongVec(type, new long[]{value}, null)));
        // Leave query cursors fetchable so closing an unread result must not fetch or drain it.
        return new QuackMessage.PrepareResponse(MessageHeader.of(MessageType.PREPARE_RESPONSE),
                List.of(type), List.of(update ? "Count" : "value"), !update,
                List.of(chunk), new HugeIntParts(17, 23));
    }

    private static final class ScriptedTransport implements QuackTransport {
        private final Deque<Object> replies;
        private final List<String> statements = new ArrayList<>();
        private Runnable beforePrepare = () -> { };

        private ScriptedTransport(Object... replies) {
            this.replies = new ArrayDeque<>(List.of(replies));
        }

        @Override
        public QuackMessage send(QuackMessage request) {
            if (request instanceof QuackMessage.ConnectionRequest) {
                return new QuackMessage.ConnectionResponse(
                        MessageHeader.of(MessageType.CONNECTION_RESPONSE).withConnectionId("execution-test"),
                        Optional.empty(), Optional.empty(), Optional.empty());
            }
            if (request instanceof QuackMessage.PrepareRequest prepare) {
                statements.add(prepare.sql());
                beforePrepare.run();
                assertFalse(replies.isEmpty(), "Unexpected SQL: " + prepare.sql());
                Object reply = replies.removeFirst();
                if (reply instanceof RuntimeException failure) throw failure;
                return (QuackMessage) reply;
            }
            if (request instanceof QuackMessage.DisconnectMessage) {
                return new QuackMessage.SuccessResponse(MessageHeader.of(MessageType.SUCCESS_RESPONSE));
            }
            throw new AssertionError("Unexpected request: " + request.getClass().getSimpleName());
        }
    }
}

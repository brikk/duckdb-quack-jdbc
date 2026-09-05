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

import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Manual-commit transaction handling (issue #4). With autoCommit off the
 * connection must lazily open a server-side transaction before the first
 * statement, otherwise a later {@code commit()} fails on the server with
 * "cannot commit - no transaction is active".
 */
class QuackConnectionTransactionTest {

    @Test
    void manualCommitOpensTransactionLazilyAndCommits() throws SQLException {
        ScriptedTransport transport = new ScriptedTransport();
        try (QuackConnection connection = connect(transport)) {
            connection.setAutoCommit(false);
            assertTrue(transport.statements.isEmpty(),
                    "setAutoCommit alone must not touch the server");

            try (Statement s = connection.createStatement()) {
                s.execute("UPDATE t1 SET v = 'x'");
            }
            connection.commit();
        }

        assertEquals(List.of("BEGIN TRANSACTION", "UPDATE t1 SET v = 'x'", "COMMIT"),
                transport.statements);
    }

    @Test
    void commitWithoutPendingStatementsIsNoOp() throws SQLException {
        ScriptedTransport transport = new ScriptedTransport();
        try (QuackConnection connection = connect(transport)) {
            connection.setAutoCommit(false);
            connection.commit();
            connection.rollback();
        }

        assertEquals(List.of(), transport.statements);
    }

    @Test
    void rollbackEndsTransactionAndNextStatementBeginsAgain() throws SQLException {
        ScriptedTransport transport = new ScriptedTransport();
        try (QuackConnection connection = connect(transport)) {
            connection.setAutoCommit(false);
            try (Statement s = connection.createStatement()) {
                s.execute("INSERT INTO t1 VALUES (1)");
                connection.rollback();
                s.execute("INSERT INTO t1 VALUES (2)");
                connection.commit();
            }
        }

        assertEquals(List.of(
                "BEGIN TRANSACTION", "INSERT INTO t1 VALUES (1)", "ROLLBACK",
                "BEGIN TRANSACTION", "INSERT INTO t1 VALUES (2)", "COMMIT"),
                transport.statements);
    }

    @Test
    void enablingAutoCommitCommitsActiveTransaction() throws SQLException {
        ScriptedTransport transport = new ScriptedTransport();
        try (QuackConnection connection = connect(transport)) {
            connection.setAutoCommit(false);
            try (Statement s = connection.createStatement()) {
                s.execute("INSERT INTO t1 VALUES (1)");
            }
            connection.setAutoCommit(true);

            try (Statement s = connection.createStatement()) {
                s.execute("SELECT 1");
            }
        }

        assertEquals(List.of(
                "BEGIN TRANSACTION", "INSERT INTO t1 VALUES (1)", "COMMIT",
                "SELECT 1"),
                transport.statements);
    }

    @Test
    void autoCommitModeSendsNoTransactionCommands() throws SQLException {
        ScriptedTransport transport = new ScriptedTransport();
        try (QuackConnection connection = connect(transport)) {
            try (Statement s = connection.createStatement()) {
                s.execute("SELECT 1");
            }
            connection.commit();
            connection.rollback();
        }

        assertEquals(List.of("SELECT 1"), transport.statements);
    }

    @Test
    void appendStartsAndReusesTheOwningConnectionsTransaction() throws SQLException {
        ScriptedTransport transport = new ScriptedTransport();
        try (QuackConnection connection = connect(transport)) {
            connection.setAutoCommit(false);
            connection.session().appendChunk("main", "t1", chunk());
            connection.session().appendChunk("main", "t1", chunk());
            connection.rollback();
            connection.session().appendChunk("main", "t1", chunk());
            connection.commit();
            connection.session().appendChunk("main", "t1", chunk());
            connection.setAutoCommit(true);
            connection.session().appendChunk("main", "t1", chunk());
        }
        assertEquals(List.of("BEGIN TRANSACTION", "APPEND t1", "APPEND t1", "ROLLBACK",
                "BEGIN TRANSACTION", "APPEND t1", "COMMIT",
                "BEGIN TRANSACTION", "APPEND t1", "COMMIT", "APPEND t1"), transport.statements);
    }

    @Test
    void failedBeginPreventsAppendAndCanBeRetried() throws SQLException {
        ScriptedTransport transport = new ScriptedTransport();
        try (QuackConnection connection = connect(transport)) {
            connection.setAutoCommit(false);
            transport.failBegin = true;
            assertThrows(QuackException.class,
                    () -> connection.session().appendChunk("main", "t1", chunk()));
            assertEquals(List.of("BEGIN TRANSACTION"), transport.statements);
            transport.failBegin = false;
            connection.session().appendChunk("main", "t1", chunk());
            connection.rollback();
        }
        assertEquals(List.of("BEGIN TRANSACTION", "BEGIN TRANSACTION", "APPEND t1", "ROLLBACK"),
                transport.statements);
    }

    @Test
    void standaloneSessionDoesNotAddTransactionCommands() {
        ScriptedTransport transport = new ScriptedTransport();
        try (QuackSession session = QuackSession.connect(QuackUri.parse("jdbc:quack://example.test"), transport)) {
            session.appendChunk("main", "t1", chunk());
            session.cursor("BEGIN TRANSACTION").close();
            session.appendChunk("main", "t1", chunk());
            session.cursor("ROLLBACK").close();
        }
        assertEquals(List.of("APPEND t1", "BEGIN TRANSACTION", "APPEND t1", "ROLLBACK"),
                transport.statements);
    }

    private static DataChunk chunk() {
        LogicalType type = LogicalType.of(LogicalTypeId.INTEGER);
        return new DataChunk(1, List.of(type), List.of(new DecodedVector.IntVec(type, new int[]{42}, null)));
    }

    private static QuackConnection connect(ScriptedTransport transport) {
        return new QuackConnection(QuackUri.parse("jdbc:quack://example.test:9494"),
                uri -> transport);
    }

    /** Replies success to everything and records the SQL of each PREPARE_REQUEST. */
    private static final class ScriptedTransport implements QuackTransport {

        final List<String> statements = new ArrayList<>();
        boolean failBegin;

        @Override
        public QuackMessage send(QuackMessage request) {
            long clientQueryId = request.header().clientQueryId().orElse(0L);
            if (request instanceof QuackMessage.ConnectionRequest) {
                return new QuackMessage.ConnectionResponse(
                        MessageHeader.of(MessageType.CONNECTION_RESPONSE)
                                .withConnectionId("tx-test")
                                .withClientQueryId(clientQueryId),
                        Optional.empty(), Optional.empty(), Optional.empty());
            }
            if (request instanceof QuackMessage.PrepareRequest prepare) {
                statements.add(prepare.sql());
                if (failBegin && prepare.sql().equals("BEGIN TRANSACTION")) {
                    throw new QuackException("simulated BEGIN failure");
                }
                return new QuackMessage.PrepareResponse(
                        MessageHeader.of(MessageType.PREPARE_RESPONSE)
                                .withConnectionId("tx-test")
                                .withClientQueryId(clientQueryId),
                        List.of(), List.of(), false, List.of(),
                        new HugeIntParts(0, 0));
            }
            if (request instanceof QuackMessage.AppendRequest append) {
                statements.add("APPEND " + append.tableName());
            }
            if (request instanceof QuackMessage.DisconnectMessage || request instanceof QuackMessage.AppendRequest) {
                return new QuackMessage.SuccessResponse(
                        MessageHeader.of(MessageType.SUCCESS_RESPONSE)
                                .withConnectionId("tx-test")
                                .withClientQueryId(clientQueryId));
            }
            throw new AssertionError("unexpected request: " + request.getClass().getSimpleName());
        }
    }
}

package com.gizmodata.quack.jdbc.it;

import com.gizmodata.quack.jdbc.message.DataChunk;
import com.gizmodata.quack.jdbc.message.DecodedVector;
import com.gizmodata.quack.jdbc.sql.QuackConnection;
import com.gizmodata.quack.jdbc.type.LogicalType;
import com.gizmodata.quack.jdbc.type.LogicalTypeId;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIf;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Manual-commit transactions against a live Quack server (issue #4).
 * DataGrip and DBeaver flip autoCommit off, run DML, then call
 * {@code Connection.commit()} — the driver must have opened a real
 * server-side transaction by then.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.gizmodata.quack.jdbc.it.QuackIntegrationTest#duckdbAvailable")
public class TransactionIntegrationTest {

    private QuackServerFixture server;

    @BeforeAll
    void startServer() throws Exception {
        server = QuackServerFixture.tryStart();
        assertNotNull(server);
    }

    @AfterAll
    void stopServer() {
        if (server != null) server.close();
    }

    private Connection connect() throws SQLException {
        return DriverManager.getConnection(server.jdbcUrl());
    }

    @Test
    void manualCommitPersistsChanges() throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE tx_commit (id INTEGER)");
            c.setAutoCommit(false);
            s.execute("INSERT INTO tx_commit VALUES (1)");
            c.commit();
            c.setAutoCommit(true);
            assertEquals(1, countRows(s, "tx_commit"));
        }
    }

    @Test
    void rollbackDiscardsChanges() throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE tx_rollback (id INTEGER)");
            c.setAutoCommit(false);
            s.execute("INSERT INTO tx_rollback VALUES (1)");
            c.rollback();
            c.setAutoCommit(true);
            assertEquals(0, countRows(s, "tx_rollback"));
        }
    }

    @Test
    void commitWithNoPendingWorkDoesNotError() throws Exception {
        // The exact DataGrip sequence from issue #4: commit() arriving
        // without any statement having run in the transaction.
        try (Connection c = connect()) {
            c.setAutoCommit(false);
            assertDoesNotThrow(() -> c.commit());
            assertDoesNotThrow(() -> c.rollback());
        }
    }

    @Test
    void updateThenCommitMatchesDataGripFlow() throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE tx_update (id INTEGER, v VARCHAR)");
            s.execute("INSERT INTO tx_update VALUES (1, 'aa'), (2, 'vv')");
            c.setAutoCommit(false);
            s.execute("UPDATE tx_update SET v = 'zz' WHERE id = 2");
            assertDoesNotThrow(c::commit);
            c.setAutoCommit(true);
            try (ResultSet rs = s.executeQuery(
                    "SELECT v FROM tx_update WHERE id = 2")) {
                assertTrue(rs.next());
                assertEquals("zz", rs.getString(1));
            }
        }
    }

    @Test
    void appendAsFirstOperationHonorsRollbackCommitAndAutoCommit() throws Exception {
        LogicalType type = LogicalType.of(LogicalTypeId.INTEGER);
        DataChunk chunk = new DataChunk(1, List.of(type), List.of(
                new DecodedVector.IntVec(type, new int[]{42}, null)));
        try (QuackConnection c = (QuackConnection) connect(); Statement s = c.createStatement();
             Connection observer = connect(); Statement other = observer.createStatement()) {
            s.execute("CREATE TABLE tx_append (v INTEGER)");
            c.setAutoCommit(false);
            c.session().appendChunk("main", "tx_append", chunk);
            assertEquals(0, countRows(other, "tx_append"));
            c.rollback();
            assertEquals(0, countRows(other, "tx_append"));

            c.session().appendChunk("main", "tx_append", chunk);
            c.session().appendChunk("main", "tx_append", chunk);
            assertEquals(0, countRows(other, "tx_append"));
            c.commit();
            assertEquals(2, countRows(other, "tx_append"));

            c.session().appendChunk("main", "tx_append", chunk);
            c.rollback();
            assertEquals(2, countRows(other, "tx_append"));

            c.session().appendChunk("main", "tx_append", chunk);
            c.setAutoCommit(true);
            assertEquals(3, countRows(other, "tx_append"));
            c.session().appendChunk("main", "tx_append", chunk);
            assertEquals(4, countRows(other, "tx_append"));
        }
    }

    private static int countRows(Statement s, String table) throws SQLException {
        try (ResultSet rs = s.executeQuery("SELECT count(*) FROM " + table)) {
            assertTrue(rs.next());
            return rs.getInt(1);
        }
    }
}

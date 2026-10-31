package com.gizmodata.quack.jdbc.it;

import com.gizmodata.quack.jdbc.sql.QuackConnection;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.gizmodata.quack.jdbc.it.QuackIntegrationTest#duckdbAvailable")
public class TriggeredCountIntegrationTest {
    private QuackServerFixture server;
    @BeforeAll void start() throws Exception { server = QuackServerFixture.tryStart(); assertNotNull(server); }
    @AfterAll void stop() { if (server != null) server.close(); }

    private QuackConnection connect() throws SQLException {
        return (QuackConnection) DriverManager.getConnection(server.jdbcUrl());
    }

    @Test
    void triggeredInsertCountsDoNotReplayWritesOrCountAuditRows() throws Exception {
        try (var c = connect(); var s = c.createStatement()) {
            if (c.session().protocolVersion() != 3) return; // Triggers are a DuckDB 2.0 feature.
            s.execute("CREATE TABLE insert_target(id INTEGER)");
            s.execute("CREATE TABLE insert_audit(id INTEGER)");
            s.execute("CREATE TRIGGER insert_ai AFTER INSERT ON insert_target FOR EACH ROW "
                    + "INSERT INTO insert_audit VALUES(NEW.id)");
            assertFalse(s.execute("INSERT INTO insert_target VALUES(1),(2)"));
            assertEquals(2, s.getUpdateCount());
            assertNull(s.getResultSet());
            assertFalse(s.getMoreResults());
            assertEquals(-1, s.getUpdateCount());
            assertEquals(2, s.executeUpdate("INSERT INTO insert_target VALUES(3),(4)"));
            try (var p = c.prepareStatement("INSERT INTO insert_target VALUES(?)")) {
                p.setInt(1, 5);
                assertFalse(p.execute());
                assertEquals(1, p.getUpdateCount());
                p.setInt(1, 6);
                assertEquals(1, p.executeUpdate());
                p.setInt(1, 7); p.addBatch();
                p.setInt(1, 8); p.addBatch();
                assertArrayEquals(new int[]{1, 1}, p.executeBatch());
            }
            assertEquals(8, scalar(s, "SELECT count(*) FROM insert_target"));
            assertEquals(8, scalar(s, "SELECT count(*) FROM insert_audit"));
            assertEquals(36, scalar(s, "SELECT sum(id) FROM insert_audit"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"UPDATE", "DELETE"})
    void preparedTriggeredUpdateDeleteAndZeroMatchCounts(String operation) throws Exception {
        try (var c = connect(); var s = c.createStatement()) {
            if (c.session().protocolVersion() != 3) return;
            String table = operation.toLowerCase(java.util.Locale.ROOT) + "_target";
            String audit = table + "_audit";
            s.execute("CREATE TABLE " + table + "(id INTEGER,v INTEGER)");
            s.execute("CREATE TABLE " + audit + "(id INTEGER)");
            s.executeUpdate("INSERT INTO " + table + " VALUES(1,10),(2,20),(3,30)");
            s.execute("CREATE TRIGGER " + table + "_after AFTER " + operation + " ON " + table
                    + " REFERENCING OLD TABLE AS old_rows FOR EACH STATEMENT INSERT INTO "
                    + audit + " SELECT id FROM old_rows");
            String sql = operation.equals("UPDATE") ? "UPDATE " + table + " SET v=v+1 WHERE id<=?"
                    : "DELETE FROM " + table + " WHERE id<=?";
            try (var p = c.prepareStatement(sql)) {
                p.setInt(1, 2);
                assertFalse(p.execute());
                assertEquals(2, p.getUpdateCount());
                assertNull(p.getResultSet());
                p.setInt(1, 0);
                assertEquals(0, p.executeUpdate());
                assertEquals(2, scalar(s, "SELECT count(*) FROM " + audit));
                p.setInt(1, operation.equals("UPDATE") ? 1 : 3);
                assertEquals(1, p.executeUpdate());
            }
            assertEquals(operation.equals("UPDATE") ? 3 : 0, scalar(s, "SELECT count(*) FROM " + table));
            if (operation.equals("UPDATE")) assertEquals(63, scalar(s, "SELECT sum(v) FROM " + table));
            assertEquals(3, scalar(s, "SELECT count(*) FROM " + audit));
            assertEquals(operation.equals("UPDATE") ? 4 : 6, scalar(s, "SELECT sum(id) FROM " + audit));
        }
    }

    @Test
    void selectCountsAndReturningNamedLikeCountsStayResultSets() throws Exception {
        try (var c = connect(); var s = c.createStatement()) {
            s.execute("CREATE TABLE count_shapes(id INTEGER)");
            s.executeUpdate("INSERT INTO count_shapes VALUES(1),(2)");
            for (String projection : List.of("count(*)", "count(*) AS Count", "count(*) AS rows_affected",
                    "count(*) AS \"count_star()\"")) {
                assertTrue(s.execute("SELECT " + projection + " FROM count_shapes"));
                assertEquals(-1, s.getUpdateCount());
                try (var rows = s.getResultSet()) {
                    assertTrue(rows.next()); assertEquals(2, rows.getLong(1)); assertFalse(rows.next());
                }
            }
            if (c.session().protocolVersion() == 3) {
                s.execute("CREATE TABLE shapes_audit(id INTEGER)");
                s.execute("CREATE TRIGGER shapes_ai AFTER INSERT ON count_shapes REFERENCING NEW TABLE AS n "
                        + "FOR EACH STATEMENT INSERT INTO shapes_audit SELECT id FROM n");
                s.execute("CREATE TRIGGER shapes_au AFTER UPDATE ON count_shapes REFERENCING NEW TABLE AS n "
                        + "FOR EACH STATEMENT INSERT INTO shapes_audit SELECT id FROM n");
                s.execute("CREATE TRIGGER shapes_ad AFTER DELETE ON count_shapes REFERENCING OLD TABLE AS o "
                        + "FOR EACH STATEMENT INSERT INTO shapes_audit SELECT id FROM o");
            }
            for (String sql : List.of("INSERT INTO count_shapes VALUES(3) RETURNING id AS Count",
                    "UPDATE count_shapes SET id=id WHERE id=3 RETURNING id AS rows_affected",
                    "DELETE FROM count_shapes WHERE id=3 RETURNING id AS \"count_star()\"")) {
                assertTrue(s.execute(sql), sql);
                assertEquals(-1, s.getUpdateCount());
                try (var rows = s.getResultSet()) {
                    assertTrue(rows.next()); assertEquals(3, rows.getInt(1)); assertFalse(rows.next());
                }
            }
            assertTrue(s.execute("UPDATE count_shapes SET id=id WHERE false RETURNING id AS Count"));
            assertEquals(-1, s.getUpdateCount());
            try (var rows = s.getResultSet()) { assertFalse(rows.next()); }
            assertEquals(2, scalar(s, "SELECT count(*) FROM count_shapes"));
            if (c.session().protocolVersion() == 3) {
                assertEquals(3, scalar(s, "SELECT count(*) FROM shapes_audit"));
                assertEquals(9, scalar(s, "SELECT sum(id) FROM shapes_audit"));
            }
        }
    }

    @Test
    void unsupportedRowTriggerReturningRemainsAnExplicitAtomicServerError() throws Exception {
        try (var c = connect(); var s = c.createStatement()) {
            if (c.session().protocolVersion() != 3) return;
            s.execute("CREATE TABLE returning_target(id INTEGER)");
            s.execute("CREATE TABLE returning_audit(id INTEGER)");
            s.execute("CREATE TRIGGER returning_ai AFTER INSERT ON returning_target FOR EACH ROW "
                    + "INSERT INTO returning_audit VALUES(NEW.id)");
            SQLException error = assertThrows(SQLException.class,
                    () -> s.execute("INSERT INTO returning_target VALUES(1) RETURNING id AS Count"));
            assertTrue(error.getMessage().contains("RETURNING is not yet supported on tables with FOR EACH ROW triggers"));
            assertEquals(-1, s.getUpdateCount()); assertNull(s.getResultSet());
            assertEquals(0, scalar(s, "SELECT count(*) FROM returning_target"));
            assertEquals(0, scalar(s, "SELECT count(*) FROM returning_audit"));
            assertEquals(1, s.executeUpdate("INSERT INTO returning_target VALUES(2)"));
            assertEquals(1, scalar(s, "SELECT count(*) FROM returning_audit"));
        }
    }

    @Test
    void sqlContextSurvivesCtesCommentsQuotedKeywordsAndBoundStrings() throws Exception {
        try (var c = connect(); var s = c.createStatement()) {
            if (c.session().protocolVersion() != 3) return;
            s.execute("CREATE TABLE context_target(id INTEGER,note VARCHAR)");
            s.execute("CREATE TABLE context_audit(id INTEGER)");
            s.execute("CREATE TRIGGER context_ai AFTER INSERT ON context_target FOR EACH ROW "
                    + "INSERT INTO context_audit VALUES(NEW.id)");
            assertEquals(1, s.executeUpdate("/* outer /* RETURNING */ comment */ WITH \"select\"(id) AS MATERIALIZED "
                    + "(SELECT 1), update AS (SELECT id FROM \"select\") INSERT INTO context_target "
                    + "SELECT id, 'RETURNING; SELECT' FROM update"));
            assertEquals(2, s.executeUpdate("WITH RECURSIVE q(n) AS (VALUES(2) UNION ALL SELECT n+1 FROM q WHERE n<3) "
                    + "INSERT INTO context_target SELECT n, 'literal' FROM q"));
            assertEquals(1, s.executeUpdate("INSERT INTO context_target VALUES(4,$tag$ RETURNING Count; $tag$)"));
            try (var p = c.prepareStatement("UPDATE context_target SET note=? WHERE id=?")) {
                p.setString(1, "RETURNING 'quoted' /* text */ ;"); p.setInt(2, 1);
                assertEquals(1, p.executeUpdate());
            }
            assertEquals(4, scalar(s, "SELECT count(*) FROM context_target"));
            assertEquals(4, scalar(s, "SELECT count(*) FROM context_audit"));
            assertEquals(10, scalar(s, "SELECT sum(id) FROM context_audit"));
        }
    }

    @Test
    void triggerFailureAndExplicitRollbackRetainAtomicSourceAndAuditEffects() throws Exception {
        try (var c = connect(); var s = c.createStatement()) {
            if (c.session().protocolVersion() != 3) return;
            s.execute("CREATE TABLE atomic_target(id INTEGER)");
            s.execute("CREATE TABLE atomic_audit(id INTEGER CHECK(id>0))");
            s.execute("CREATE TRIGGER atomic_ai AFTER INSERT ON atomic_target FOR EACH ROW "
                    + "INSERT INTO atomic_audit VALUES(NEW.id)");
            c.setAutoCommit(false);
            assertEquals(1, s.executeUpdate("INSERT INTO atomic_target VALUES(1)"));
            assertThrows(SQLException.class, () -> s.executeUpdate("INSERT INTO atomic_target VALUES(-1)"));
            assertEquals(-1, s.getUpdateCount()); assertNull(s.getResultSet());
            assertTrue(assertThrows(SQLException.class, () -> s.execute("SELECT 42")).getMessage().contains("aborted"));
            c.rollback(); c.setAutoCommit(true);
            assertEquals(0, scalar(s, "SELECT count(*) FROM atomic_target"));
            assertEquals(0, scalar(s, "SELECT count(*) FROM atomic_audit"));
            assertEquals(1, s.executeUpdate("INSERT INTO atomic_target VALUES(2)"));
            assertEquals(1, scalar(s, "SELECT count(*) FROM atomic_target"));
            assertEquals(1, scalar(s, "SELECT count(*) FROM atomic_audit"));
        }
    }

    @Test
    @Tag("oracle")
    void rawCursorAndIndependentNativeQuackClientExposeTheSameCount() throws Exception {
        try (var c = connect(); var s = c.createStatement()) {
            if (c.session().protocolVersion() != 3) return;
            assertTrue(DuckDbOracle.available());
            s.execute("CREATE TABLE raw_target(id INTEGER)");
            s.execute("CREATE TABLE raw_audit(id INTEGER)");
            s.execute("CREATE TRIGGER raw_ai AFTER INSERT ON raw_target FOR EACH ROW INSERT INTO raw_audit VALUES(NEW.id)");
            try (var cursor = c.session().cursor("INSERT INTO raw_target VALUES(1),(2)")) {
                assertEquals(List.of("count_star()"), cursor.columnNames());
                var chunks = cursor.drainAll();
                assertEquals(1, chunks.size()); assertEquals(1, chunks.get(0).rowCount());
                assertEquals(2, ((Number) chunks.get(0).columns().get(0).getObject(0)).longValue());
            }
            Path extensions = Files.createTempDirectory(Path.of("target"), "native-quack-counts-").toAbsolutePath();
            try (var nativeConnection = DuckDbOracle.newConnection(); var nativeStatement = nativeConnection.createStatement()) {
                nativeStatement.execute("SET extension_directory='" + extensions.toString().replace("'", "''") + "'");
                nativeStatement.execute("INSTALL httpfs FROM core; LOAD httpfs; INSTALL quack FROM core; LOAD quack");
                try (var identity = nativeStatement.executeQuery("SELECT extension_version,installed_from "
                        + "FROM duckdb_extensions() WHERE extension_name='quack'")) {
                    assertTrue(identity.next());
                    assertTrue(identity.getString(1).startsWith("f96420ed46"));
                    assertEquals("core", identity.getString(2));
                }
                String query = "SELECT * FROM quack_query('quack:127.0.0.1:" + server.port()
                        + "','INSERT INTO raw_target VALUES(3),(4)',token=>'" + server.token() + "')";
                try (var rows = nativeStatement.executeQuery(query)) {
                    assertEquals("count_star()", rows.getMetaData().getColumnName(1));
                    assertTrue(rows.next()); assertEquals(2, rows.getLong(1)); assertFalse(rows.next());
                }
            }
            assertEquals(4, scalar(s, "SELECT count(*) FROM raw_target"));
            assertEquals(4, scalar(s, "SELECT count(*) FROM raw_audit"));
            assertEquals(10, scalar(s, "SELECT sum(id) FROM raw_audit"));
        }
    }

    private static long scalar(Statement statement, String sql) throws SQLException {
        try (var rows = statement.executeQuery(sql)) {
            assertTrue(rows.next()); long value = rows.getLong(1); assertFalse(rows.next()); return value;
        }
    }
}

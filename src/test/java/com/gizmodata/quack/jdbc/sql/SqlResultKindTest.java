package com.gizmodata.quack.jdbc.sql;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;

class SqlResultKindTest {
    @Test
    void dmlCountsIgnoreNonCodeReturningTextAndNestedQueries() {
        for (String sql : List.of(
                "INSERT INTO t VALUES(1)", "update t set v=1", "DELETE FROM t WHERE false",
                "MERGE INTO t USING s ON t.id=s.id WHEN MATCHED THEN DELETE",
                "/* outer /* RETURNING */ comment */ -- SELECT\nINSERT INTO t VALUES(1)",
                "INSERT INTO t VALUES('RETURNING; ''quoted''')",
                "INSERT INTO \"RETURNING\" VALUES(E'quoted\\' RETURNING literal')",
                "INSERT INTO t VALUES($tag$ ); RETURNING x; $tag$)",
                "INSERT INTO t VALUES($$ RETURNING $$)",
                "UPDATE t SET \"RETURNING\"=(SELECT v FROM s WHERE note='RETURNING')",
                "INSERT INTO t VALUES(E'first'\n'\\' RETURNING hidden')",
                "\ufeff\u00a0INSERT\u2003INTO t VALUES(1); /* trailing */ ;")) {
            assertEquals(SqlResultKind.DML_COUNT, SqlResultKind.of(sql), sql);
        }
    }

    @Test
    void cteNamesAndBodiesDoNotBecomeTheOuterCommand() {
        for (String sql : List.of(
                "WITH update AS (SELECT 1) INSERT INTO t SELECT * FROM update",
                "WITH \"select\"(id) AS MATERIALIZED (SELECT 1), delete AS NOT MATERIALIZED "
                        + "(SELECT id FROM \"select\") UPDATE t SET v=1",
                "WITH RECURSIVE q(n) AS (VALUES(1) UNION ALL SELECT n+1 FROM q WHERE n<3) DELETE FROM t",
                "WITH RECURSIVE q(n) USING KEY(n) AS (SELECT 1) INSERT INTO t SELECT * FROM q")) {
            assertEquals(SqlResultKind.DML_COUNT, SqlResultKind.of(sql), sql);
        }
        for (String sql : List.of("WITH update AS (SELECT 1) SELECT * FROM update",
                "WITH insert AS (SELECT 1) FROM insert SELECT count(*)",
                "WITH q AS (SELECT 'RETURNING') INSERT INTO t SELECT * FROM q RETURNING id")) {
            assertEquals(SqlResultKind.ROWS_OR_UNKNOWN, SqlResultKind.of(sql), sql);
        }
    }

    @Test
    void selectsReturningAndMultiStatementResultsAreNeverCountCommands() {
        for (String sql : List.of("SELECT count(*)", "SELECT 2 AS Count", "FROM t SELECT count(*)",
                "VALUES (2)", "TABLE t", "CALL p()", "EXPLAIN UPDATE t SET v=1",
                "INSERT INTO t VALUES(1) RETURNING id AS Count",
                "UPDATE t SET v='RETURNING' RETURNING v AS rows_affected",
                "DELETE FROM t RETURNING 1 AS \"count_star()\"",
                "MERGE INTO t USING s ON true WHEN MATCHED THEN DELETE RETURNING *",
                "INSERT INTO t VALUES('first'\n'\\') RETURNING id AS Count",
                "INSERT INTO t VALUES(1); SELECT 2 AS Count",
                "UPDATE t SET v=1; DELETE FROM t")) {
            assertEquals(SqlResultKind.ROWS_OR_UNKNOWN, SqlResultKind.of(sql), sql);
        }
    }

    @Test
    void ddlAndControlCommandsKeepTheirLegacyCountContext() {
        for (String sql : List.of("CREATE TABLE t AS SELECT 1", "DROP TABLE t", "ALTER TABLE t ADD COLUMN a INT",
                "COPY t TO 'RETURNING;file'", "BEGIN TRANSACTION", "COMMIT", "ROLLBACK", "SET threads=1")) {
            assertEquals(SqlResultKind.OTHER_COUNT, SqlResultKind.of(sql), sql);
        }
    }

    @Test
    void malformedOrUnknownContextsRemainRowsWithoutLocalSqlErrors() {
        for (String sql : List.of("", "/* unterminated", "INSERT INTO t VALUES('unterminated)",
                "INSERT INTO t VALUES($tag$unterminated)", "INSERT INTO t VALUES((1)",
                "INSERT INTO t VALUES([1))", "WITH q AS SELECT 1 UPDATE t SET v=1",
                "WITH q AS (SELECT 1), UPDATE t SET v=1", "mystery command")) {
            assertEquals(SqlResultKind.ROWS_OR_UNKNOWN, SqlResultKind.of(sql), sql);
        }
    }
}

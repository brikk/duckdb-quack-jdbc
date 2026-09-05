package com.gizmodata.quack.jdbc.it;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.parallel.Isolated;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Array;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Calendar;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("integration")
@Tag("oracle")
@Isolated("Changes the JVM default timezone")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.gizmodata.quack.jdbc.it.QuackIntegrationTest#duckdbAvailable")
public class OracleParityIntegrationTest {

    private QuackServerFixture server;
    private Connection quack;
    private Connection oracle;

    @BeforeAll
    void setUp() throws Exception {
        Assumptions.assumeTrue(DuckDbOracle.available(),
                "duckdb_jdbc driver not on the test classpath (run with -Poracle)");
        server = QuackServerFixture.tryStart();
        Assumptions.assumeTrue(server != null, "duckdb binary not available for the Quack server fixture");
        quack = DriverManager.getConnection(server.jdbcUrl());
        oracle = DuckDbOracle.newConnection();
        for (Connection c : new Connection[]{quack, oracle}) {
            try (Statement s = c.createStatement()) {
                s.execute("CREATE TYPE mood AS ENUM ('x', 'y', 'z')");
            }
        }
    }

    @AfterAll
    void tearDown() throws Exception {
        if (quack != null) quack.close();
        if (oracle != null) oracle.close();
        if (server != null) server.close();
    }

    @Test
    @DisplayName("Column types match duckdb-jdbc exactly across scalars, arrays, and STRUCT/MAP/ENUM")
    void columnTypesMatchOracle() throws Exception {
        String[] queries = {
                "SELECT 42 AS a",
                "SELECT 1.23::DECIMAL(5,2) AS a",
                "SELECT '12345678-1234-1234-1234-123456789012'::UUID AS a",
                "SELECT [10, 20, 30]::INTEGER[] AS a",
                "SELECT [[1, 2], [3]]::INTEGER[][] AS a",
                "SELECT [1.23, 4.56]::DECIMAL(5,2)[] AS a",
                "SELECT [TIMESTAMP '2020-01-01 00:00:00'] AS a",
                "SELECT ['a', 'b']::VARCHAR[] AS a",
                "SELECT ['12345678-1234-1234-1234-123456789012'::UUID] AS a",
                "SELECT [1.5, 2.5]::DOUBLE[2] AS a",
                "SELECT [[1, 2], [3, 4]]::INTEGER[2][] AS a",
                "SELECT {'x': 1, 'y': 'a'} AS a",
                "SELECT [{'x': 1, 'y': 'a'}] AS a",
                "SELECT [{'x': 1}] AS a",
                "SELECT {'a': 1.23::DECIMAL(5,2), 'b': [1, 2], 'c': {'d': 1}} AS a",
                "SELECT MAP {1: 'a', 2: 'b'} AS a",
                "SELECT [MAP {1: 'a'}] AS a",
                "SELECT MAP {'k': [1, 2]} AS a",
                "SELECT 'x'::mood AS a",
                "SELECT ['x']::mood[] AS a",
                "SELECT {'m': 'x'::mood} AS a",
        };
        for (String sql : queries) {
            assertColumnTypeParity(sql);
        }
    }

    @Test
    void integerValuesAndMetadataUseNativeLosslessRepresentations() throws Exception {
        String[] names = {"TINYINT", "UTINYINT", "SMALLINT", "USMALLINT", "INTEGER", "UINTEGER",
                "BIGINT", "UBIGINT", "HUGEINT", "UHUGEINT"};
        int[] widths = {8, 8, 16, 16, 32, 32, 64, 64, 128, 128};
        try (Statement qs = quack.createStatement(); Statement os = oracle.createStatement()) {
            for (int i = 0; i < names.length; i++) {
                String name = names[i];
                boolean unsigned = name.startsWith("U");
                BigInteger limit = BigInteger.ONE.shiftLeft(widths[i] - (unsigned ? 0 : 1));
                BigInteger max = limit.subtract(BigInteger.ONE);
                BigInteger min = unsigned ? BigInteger.ZERO : limit.negate();
                String sql = "SELECT v FROM (VALUES (0, '" + min + "'::" + name + "), "
                        + "(1, 0::" + name + "), (2, '" + max + "'::" + name + "), (3, NULL::" + name + ")) t(i,v) ORDER BY i";
                try (ResultSet q = qs.executeQuery(sql); ResultSet o = os.executeQuery(sql)) {
                    ResultSetMetaData qm = q.getMetaData();
                    ResultSetMetaData om = o.getMetaData();
                    assertEquals(om.getColumnType(1), qm.getColumnType(1), name);
                    assertEquals(om.getColumnTypeName(1), qm.getColumnTypeName(1), name);
                    assertEquals(om.getColumnClassName(1), qm.getColumnClassName(1), name);
                    assertEquals(om.isSigned(1), qm.isSigned(1), name);
                    assertEquals(om.getScale(1), qm.getScale(1), name);
                    // Native underreports UBIGINT/HUGEINT/UHUGEINT precision (19/38/38).
                    assertEquals(max.toString().length(), qm.getPrecision(1), name);
                    while (o.next()) {
                        assertTrue(q.next(), name);
                        Object expected = o.getObject(1);
                        assertEquals(expected, q.getObject(1), name);
                        assertEquals(expected == null, q.wasNull(), name);
                        Object viaMetadata = switch (qm.getColumnType(1)) {
                            case Types.TINYINT -> q.getByte(1);
                            case Types.SMALLINT -> q.getShort(1);
                            case Types.INTEGER -> q.getInt(1);
                            case Types.BIGINT -> q.getLong(1);
                            default -> q.getObject(1, BigInteger.class);
                        };
                        if (q.wasNull()) viaMetadata = null;
                        assertEquals(expected, viaMetadata, name);
                        assertEquals(expected == null ? null : new BigDecimal(expected.toString()), q.getBigDecimal(1), name);
                        if (expected != null) {
                            assertTrue(qm.getColumnDisplaySize(1) >= expected.toString().length(), name);
                        }
                    }
                    assertFalse(q.next(), name);
                }
                for (String suffix : new String[]{"[]", "[3]"}) {
                    String arraySql = "SELECT ['" + min + "', '" + max + "', NULL]::" + name + suffix;
                    try (ResultSet q = qs.executeQuery(arraySql); ResultSet o = os.executeQuery(arraySql)) {
                        assertTrue(q.next());
                        assertTrue(o.next());
                        Array qa = q.getArray(1);
                        Array oa = o.getArray(1);
                        assertEquals(oa.getBaseType(), qa.getBaseType(), name + suffix);
                        assertEquals(oa.getBaseTypeName(), qa.getBaseTypeName(), name + suffix);
                        assertArrayEquals((Object[]) oa.getArray(), (Object[]) qa.getArray(), name + suffix);
                    }
                }
            }
        }
    }

    @Test
    void ordinaryCalendarTimestampConversionsMatchNative() throws Exception {
        TimeZone original = TimeZone.getDefault();
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
            Calendar la = Calendar.getInstance(TimeZone.getTimeZone("America/Los_Angeles"));
            Calendar tokyo = Calendar.getInstance(TimeZone.getTimeZone("Asia/Tokyo"));
            for (Connection c : new Connection[]{quack, oracle}) {
                try (Statement s = c.createStatement();
                     ResultSet rs = s.executeQuery("SELECT TIMESTAMP '2024-01-02 03:04:05.123456' AS v")) {
                    assertTrue(rs.next());
                    assertEquals(Instant.parse("2024-01-02T11:04:05.123456Z"), rs.getTimestamp("v", la).toInstant());
                }
                // Native's Calendar setter loses sub-millisecond digits, so parity is limited here.
                try (PreparedStatement ps = c.prepareStatement("SELECT ? AS v")) {
                    ps.setTimestamp(1, Timestamp.from(Instant.parse("2026-01-01T00:00:00.123Z")), tokyo);
                    try (ResultSet rs = ps.executeQuery()) {
                        assertTrue(rs.next());
                        assertEquals(LocalDateTime.parse("2026-01-01T09:00:00.123"), rs.getObject(1, LocalDateTime.class));
                    }
                }
            }
        } finally {
            TimeZone.setDefault(original);
        }
    }

    private void assertColumnTypeParity(String sql) throws SQLException {
        DuckDbOracle.ColumnType q = DuckDbOracle.columnType(quack, sql);
        DuckDbOracle.ColumnType o = DuckDbOracle.columnType(oracle, sql);
        assertEquals(o.typeName(), q.typeName(), "getColumnTypeName mismatch for: " + sql);
        assertEquals(o.type(), q.type(), "getColumnType mismatch for: " + sql);
    }
}

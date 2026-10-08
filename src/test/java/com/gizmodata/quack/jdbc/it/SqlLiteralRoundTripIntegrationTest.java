package com.gizmodata.quack.jdbc.it;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIf;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-trips PreparedStatement parameters (client-side SqlLiteral
 * interpolation) through a live server: set a value, SELECT it back, and
 * confirm DuckDB parses the rendered literal to the same value. Also compares
 * the result against duckdb-jdbc (which uses real bind parameters) under the
 * oracle profile.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.gizmodata.quack.jdbc.it.QuackIntegrationTest#duckdbAvailable")
public class SqlLiteralRoundTripIntegrationTest {

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
    void primitivesRoundTrip() throws Exception {
        try (Connection c = connect()) {
            assertEquals(true, roundTripBoolean(c, Boolean.TRUE));
            assertEquals(42, ((Number) roundTripObject(c, 42)).intValue());
            assertEquals(9_000_000_000L, ((Number) roundTripObject(c, 9_000_000_000L)).longValue());
            assertEquals(2.5, ((Number) roundTripObject(c, 2.5d)).doubleValue(), 1e-9);
            assertEquals(new BigDecimal("12.34"), roundTripBigDecimal(c, new BigDecimal("12.34")));
        }
    }

    @Test
    void stringsRoundTripIncludingQuotesAndInjection() throws Exception {
        try (Connection c = connect()) {
            assertEquals("hello", roundTripString(c, "hello"));
            assertEquals("O'Brien", roundTripString(c, "O'Brien"));
            assertEquals("'); DROP TABLE users; --", roundTripString(c, "'); DROP TABLE users; --"));
            assertEquals("", roundTripString(c, ""));
        }
    }

    @Test
    void parametersRespectDuckDbLexicalContexts() throws Exception {
        String[] queries = {
                "SELECT ? AS value -- ? '",
                "SELECT /* outer ? /* nested ? */ ' */ ? AS value",
                "SELECT ? AS value, E'it\\'s ?' AS literal",
                "SELECT ? AS value, E'one' -- ?\n 'two\\'?' AS literal",
                "SELECT ? AS value, $$ ' /* ? */ $$ AS literal",
                "SELECT ? AS value, $tag$ $other$ ? $tag$ AS literal",
                "SELECT ? AS value, $\u03b1$ ? $\u03b1$ AS literal",
                "SELECT ? AS value, 'it''s ?' AS \"a\"\"?\""
        };
        String value = "*/ UNION ALL SELECT 999 -- \\' ? $tag$";
        try (Connection c = connect()) {
            for (String sql : queries) {
                // The 2.0 parser no longer treats this escaped multiline string
                // continuation as valid SQL. Keep the v1 lexer regression.
                if (sql.equals(queries[3]) && ((com.gizmodata.quack.jdbc.sql.QuackConnection) c)
                        .session().protocolVersion() == 3) continue;
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    assertEquals(1, ps.getParameterMetaData().getParameterCount(), sql);
                    ps.setString(1, value);
                    try (ResultSet rs = ps.executeQuery()) {
                        assertTrue(rs.next(), sql);
                        assertEquals(value, rs.getString(1), sql);
                        assertFalse(rs.next(), sql);
                    }
                }
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT 1 /* ? ' */ WHERE ? = 0")) {
                ps.setString(1, "*/ UNION ALL SELECT 999 --");
                assertThrows(SQLException.class, ps::executeQuery);
                ps.setInt(1, 0);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(1, rs.getInt(1));
                    assertFalse(rs.next());
                }
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT -? AS value, ?^2 AS squared")) {
                ps.setInt(1, -42);
                ps.setInt(2, -2);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(42, rs.getInt(1));
                    assertEquals(4.0, rs.getDouble(2));
                }
            }
            // A replacement must not become a continuation of an earlier escape string.
            try (PreparedStatement ps = c.prepareStatement("SELECT E'prefix'\n?")) {
                ps.setString(1, "\\'; SELECT 999; --");
                assertThrows(SQLException.class, ps::executeQuery);
            }
        }
    }

    @Test
    void unicodeWhitespaceCannotChangeParameterBoundaries() throws Exception {
        try (Connection c = connect()) {
            String spaces = "\u00a0\u2000\u2001\u2002\u2003\u2004\u2005\u2006\u2007\u2008"
                    + "\u2009\u200a\u200b\u202f\u205f\u2060\u3000\ufeff";
            for (char space : spaces.toCharArray()) {
                for (String sql : new String[]{
                        "SELECT " + space + "E'\\'?' AS literal, ? AS bound",
                        "SELECT E'first'\n" + space + "'\\'?' AS literal, ? AS bound"}) {
                    SQLException error = assertThrows(SQLException.class, () -> {
                        try (PreparedStatement ps = c.prepareStatement(sql)) {
                            ps.setString(1, "; SELECT 999; --");
                            ps.execute();
                        }
                    });
                    assertTrue(error.getMessage().contains("Unicode whitespace"));
                }
            }
            for (String sql : new String[]{"SELECT ? AS bound",
                    "SELECT E'it\\'s' AS literal, ? AS bound",
                    "SELECT /* ' */ ? AS bound", "SELECT $$ ' $$ AS literal, ? AS bound"}) {
                try (PreparedStatement ps = c.prepareStatement(sql)) {
                    for (String value : new String[]{spaces, "x\u00a0y", "\\'; SELECT 999; --\u00a0",
                            "\u00a0\\'\ud834\udd1e"}) {
                        ps.setString(1, value);
                        try (ResultSet rs = ps.executeQuery()) {
                            assertTrue(rs.next());
                            assertEquals(value, rs.getString("bound"), sql);
                            assertFalse(rs.next());
                        }
                    }
                }
            }
        }
    }

    @Test
    void binaryRoundTrips() throws Exception {
        byte[] payload = {(byte) 0xCA, (byte) 0xFE, 0x00, 0x7F};
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement("SELECT ? AS a")) {
            ps.setBytes(1, payload);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertArrayEquals(payload, rs.getBytes(1));
            }
        }
    }

    @Test
    void temporalAndUuidRoundTrip() throws Exception {
        try (Connection c = connect()) {
            assertEquals(LocalDate.of(2026, 5, 13),
                    roundTripAs(c, LocalDate.of(2026, 5, 13), LocalDate.class));
            assertEquals(LocalDateTime.of(2026, 5, 13, 14, 30, 15),
                    roundTripAs(c, LocalDateTime.of(2026, 5, 13, 14, 30, 15), LocalDateTime.class));
            UUID u = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef");
            assertEquals(u, roundTripAs(c, u, UUID.class));
        }
    }

    @Test
    void nullRoundTrips() throws Exception {
        try (Connection c = connect();
             PreparedStatement ps = c.prepareStatement("SELECT ? AS a")) {
            ps.setObject(1, null);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                rs.getObject(1);
                assertTrue(rs.wasNull());
            }
        }
    }

    @Test
    void exactNumericGettersPreserveLargeIntegers() throws Exception {
        try (Connection c = connect(); PreparedStatement ps = c.prepareStatement("SELECT ?::BIGINT, ?::VARCHAR")) {
            for (long value : new long[]{9_007_199_254_740_993L, -9_007_199_254_740_993L,
                    Long.MIN_VALUE, Long.MAX_VALUE}) {
                ps.setLong(1, value);
                ps.setString(2, "18446744073709551617");
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(BigDecimal.valueOf(value), rs.getBigDecimal(1));
                    assertEquals(BigDecimal.valueOf(value), rs.getObject(1, BigDecimal.class));
                    assertEquals(new BigInteger("18446744073709551617"), rs.getObject(2, BigInteger.class));
                    assertFalse(rs.next());
                }
            }
        }
    }

    @Test
    @Tag("oracle")
    void resultsMatchOracleForBoundParameters() throws Exception {
        Assumptions.assumeTrue(DuckDbOracle.available(),
                "duckdb_jdbc driver not on the test classpath (run with -Poracle)");
        Object[] params = {
                42, 9_000_000_000L, 2.5d, new BigDecimal("12.34"),
                "O'Brien", "plain",
        };
        try (Connection quack = connect();
             Connection oracle = DuckDbOracle.newConnection()) {
            for (Object p : params) {
                assertEquals(asString(oracle, p), asString(quack, p),
                        "parameter round-trip mismatch for: " + p);
            }
        }
    }

    private boolean roundTripBoolean(Connection c, Boolean value) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT ? AS a")) {
            ps.setObject(1, value);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        }
    }

    private Object roundTripObject(Connection c, Object value) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT ? AS a")) {
            ps.setObject(1, value);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getObject(1);
            }
        }
    }

    private BigDecimal roundTripBigDecimal(Connection c, BigDecimal value) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT ? AS a")) {
            ps.setBigDecimal(1, value);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getBigDecimal(1);
            }
        }
    }

    private String roundTripString(Connection c, String value) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT ? AS a")) {
            ps.setString(1, value);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    private <T> T roundTripAs(Connection c, Object value, Class<T> as) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT ? AS a")) {
            ps.setObject(1, value);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getObject(1, as);
            }
        }
    }

    private static String asString(Connection c, Object param) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT (?)::VARCHAR AS a")) {
            ps.setObject(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }
}

package com.gizmodata.quack.jdbc.it;

import com.gizmodata.quack.jdbc.QuackProtocolException;
import com.gizmodata.quack.jdbc.message.DataChunk;
import com.gizmodata.quack.jdbc.message.DecodedVector;
import com.gizmodata.quack.jdbc.message.Validity;
import com.gizmodata.quack.jdbc.sql.QuackConnection;
import com.gizmodata.quack.jdbc.type.LogicalType;
import com.gizmodata.quack.jdbc.type.LogicalTypeId;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIf;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integration tests for the APPEND_REQUEST encoder + the public
 * {@code QuackSession.appendChunk(...)} bulk-load API.
 */
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.gizmodata.quack.jdbc.it.QuackIntegrationTest#duckdbAvailable")
public class AppendIntegrationTest {

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

    private QuackConnection connect() throws SQLException {
        return (QuackConnection) DriverManager.getConnection(server.jdbcUrl());
    }

    @Test
    void appendIntVarcharChunk() throws Exception {
        try (QuackConnection c = connect(); Statement s = c.createStatement()) {
            s.execute("DROP TABLE IF EXISTS jdbc_it_append1");
            s.execute("CREATE TABLE jdbc_it_append1 (id INTEGER, name VARCHAR)");

            LogicalType intType = LogicalType.of(LogicalTypeId.INTEGER);
            LogicalType varcharType = LogicalType.of(LogicalTypeId.VARCHAR);
            DataChunk chunk = new DataChunk(4,
                    List.of(intType, varcharType),
                    List.of(
                            new DecodedVector.IntVec(intType, new int[]{1, 2, 3, 4}, null),
                            new DecodedVector.ObjectVec(varcharType,
                                    new Object[]{"alpha", "beta", "gamma", "delta"})));

            c.session().appendChunk("main", "jdbc_it_append1", chunk);

            try (ResultSet rs = s.executeQuery(
                    "SELECT id, name FROM jdbc_it_append1 ORDER BY id")) {
                int i = 1;
                String[] expectedNames = {"alpha", "beta", "gamma", "delta"};
                while (rs.next()) {
                    assertEquals(i, rs.getInt("id"));
                    assertEquals(expectedNames[i - 1], rs.getString("name"));
                    i++;
                }
                assertEquals(5, i);
            }

            s.execute("DROP TABLE jdbc_it_append1");
        }
    }

    @Test
    void appendNullableColumn() throws Exception {
        try (QuackConnection c = connect(); Statement s = c.createStatement()) {
            s.execute("DROP TABLE IF EXISTS jdbc_it_append_null");
            s.execute("CREATE TABLE jdbc_it_append_null (v BIGINT)");

            LogicalType bigintType = LogicalType.of(LogicalTypeId.BIGINT);
            long[] validity = Validity.allValid(4);
            Validity.setNull(validity, 1);
            Validity.setNull(validity, 3);
            DataChunk chunk = new DataChunk(4,
                    List.of(bigintType),
                    List.of(new DecodedVector.LongVec(bigintType,
                            new long[]{100L, 0L, 300L, 0L}, validity)));

            c.session().appendChunk("main", "jdbc_it_append_null", chunk);

            try (ResultSet rs = s.executeQuery(
                    "SELECT v, v IS NULL AS is_null FROM jdbc_it_append_null")) {
                int row = 0;
                long[] expectedValues = {100L, 0L, 300L, 0L};
                boolean[] expectedNulls = {false, true, false, true};
                while (rs.next()) {
                    if (expectedNulls[row]) {
                        rs.getLong("v");
                        assertTrue(rs.wasNull(), "row " + row + " should be null");
                    } else {
                        assertEquals(expectedValues[row], rs.getLong("v"));
                        assertFalse(rs.wasNull(), "row " + row + " should not be null");
                    }
                    row++;
                }
                assertEquals(4, row);
            }

            s.execute("DROP TABLE jdbc_it_append_null");
        }
    }

    @Test
    void appendMixedScalarTypes() throws Exception {
        try (QuackConnection c = connect(); Statement s = c.createStatement()) {
            s.execute("DROP TABLE IF EXISTS jdbc_it_append_mix");
            s.execute("CREATE TABLE jdbc_it_append_mix (" +
                    "b BOOLEAN, i INTEGER, big BIGINT, d DOUBLE, " +
                    "dec DECIMAL(10,2), dt DATE, ts TIMESTAMP, v VARCHAR)");

            LogicalType boolType = LogicalType.of(LogicalTypeId.BOOLEAN);
            LogicalType intType = LogicalType.of(LogicalTypeId.INTEGER);
            LogicalType bigintType = LogicalType.of(LogicalTypeId.BIGINT);
            LogicalType doubleType = LogicalType.of(LogicalTypeId.DOUBLE);
            LogicalType decType = LogicalType.decimal(10, 2);
            LogicalType dateType = LogicalType.of(LogicalTypeId.DATE);
            LogicalType tsType = LogicalType.of(LogicalTypeId.TIMESTAMP);
            LogicalType varcharType = LogicalType.of(LogicalTypeId.VARCHAR);

            DataChunk chunk = new DataChunk(2,
                    List.of(boolType, intType, bigintType, doubleType,
                            decType, dateType, tsType, varcharType),
                    List.of(
                            new DecodedVector.BoolVec(boolType, new boolean[]{true, false}, null),
                            new DecodedVector.IntVec(intType, new int[]{10, 20}, null),
                            new DecodedVector.LongVec(bigintType,
                                    new long[]{1_000_000L, 2_000_000L}, null),
                            new DecodedVector.DoubleVec(doubleType,
                                    new double[]{1.5, 2.75}, null),
                            new DecodedVector.ObjectVec(decType,
                                    new Object[]{new BigDecimal("12.34"),
                                            new BigDecimal("99.99")}),
                            new DecodedVector.ObjectVec(dateType,
                                    new Object[]{LocalDate.of(2026, 1, 1),
                                            LocalDate.of(2026, 12, 31)}),
                            new DecodedVector.ObjectVec(tsType,
                                    new Object[]{
                                            LocalDateTime.of(2026, 5, 13, 9, 0, 0),
                                            LocalDateTime.of(2026, 5, 13, 17, 30, 0)}),
                            new DecodedVector.ObjectVec(varcharType,
                                    new Object[]{"first", "second"})));

            c.session().appendChunk("main", "jdbc_it_append_mix", chunk);

            try (ResultSet rs = s.executeQuery(
                    "SELECT b, i, big, d, dec, dt, ts, v FROM jdbc_it_append_mix ORDER BY i")) {
                assertTrue(rs.next());
                assertTrue(rs.getBoolean(1));
                assertEquals(10, rs.getInt(2));
                assertEquals(1_000_000L, rs.getLong(3));
                assertEquals(1.5, rs.getDouble(4), 1e-9);
                assertEquals(new BigDecimal("12.34"), rs.getBigDecimal(5));
                assertEquals(LocalDate.of(2026, 1, 1), rs.getObject(6, LocalDate.class));
                assertEquals("first", rs.getString(8));

                assertTrue(rs.next());
                assertFalse(rs.getBoolean(1));
                assertEquals(20, rs.getInt(2));
                assertEquals("second", rs.getString(8));

                assertFalse(rs.next());
            }

            s.execute("DROP TABLE jdbc_it_append_mix");
        }
    }

    @Test
    void appendIsFasterThanInsert() throws Exception {
        // Sanity check: a 5000-row APPEND should not take materially longer
        // than the same data via single statements (and is structurally
        // much faster per round-trip). Mostly here to make sure the path
        // actually works at non-trivial scale.
        try (QuackConnection c = connect(); Statement s = c.createStatement()) {
            s.execute("DROP TABLE IF EXISTS jdbc_it_append_big");
            s.execute("CREATE TABLE jdbc_it_append_big (i INTEGER)");

            int rows = 5000;
            int[] arr = new int[rows];
            for (int i = 0; i < rows; i++) arr[i] = i;
            LogicalType intType = LogicalType.of(LogicalTypeId.INTEGER);
            DataChunk chunk = new DataChunk(rows, List.of(intType), List.of(
                    new DecodedVector.IntVec(intType, arr, null)));

            c.session().appendChunk("main", "jdbc_it_append_big", chunk);

            try (ResultSet rs = s.executeQuery(
                    "SELECT COUNT(*) AS n, MIN(i) AS lo, MAX(i) AS hi FROM jdbc_it_append_big")) {
                assertTrue(rs.next());
                assertEquals(rows, rs.getInt("n"));
                assertEquals(0, rs.getInt("lo"));
                assertEquals(rows - 1, rs.getInt("hi"));
            }
            s.execute("DROP TABLE jdbc_it_append_big");
        }
    }

    @Test
    void appendBlobAndBytes() throws Exception {
        try (QuackConnection c = connect(); Statement s = c.createStatement()) {
            s.execute("DROP TABLE IF EXISTS jdbc_it_append_blob");
            s.execute("CREATE TABLE jdbc_it_append_blob (b BLOB)");

            LogicalType blobType = LogicalType.of(LogicalTypeId.BLOB);
            byte[] payload = {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE};
            DataChunk chunk = new DataChunk(1, List.of(blobType), List.of(
                    new DecodedVector.ObjectVec(blobType, new Object[]{payload})));

            c.session().appendChunk("main", "jdbc_it_append_blob", chunk);

            try (ResultSet rs = s.executeQuery("SELECT b FROM jdbc_it_append_blob")) {
                assertTrue(rs.next());
                assertArrayEquals(payload, rs.getBytes(1));
            }
            s.execute("DROP TABLE jdbc_it_append_blob");
        }
    }

    @Test
    void outOfRangeAppendDoesNotWriteAnyRows() throws Exception {
        try (QuackConnection c = connect(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE jdbc_it_append_range (v DECIMAL(4,0))");
            LogicalType type = LogicalType.decimal(4, 0);
            DataChunk invalid = new DataChunk(2, List.of(type), List.of(
                    new DecodedVector.ObjectVec(type, new Object[]{BigDecimal.ONE, new BigDecimal("40000")})));
            assertThrows(QuackProtocolException.class,
                    () -> c.session().appendChunk("main", "jdbc_it_append_range", invalid));
            try (ResultSet rs = s.executeQuery("SELECT count(*) AS n FROM jdbc_it_append_range")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1));
            }
            DataChunk valid = new DataChunk(2, List.of(type), List.of(
                    new DecodedVector.ObjectVec(type, new Object[]{new BigDecimal("-9999"), new BigDecimal("9999")})));
            c.session().appendChunk("main", "jdbc_it_append_range", valid);
            try (ResultSet rs = s.executeQuery("SELECT v::VARCHAR FROM jdbc_it_append_range ORDER BY v")) {
                assertTrue(rs.next());
                assertEquals("-9999", rs.getString(1));
                assertTrue(rs.next());
                assertEquals("9999", rs.getString(1));
                assertFalse(rs.next());
            }
        }
    }

    @Test
    void decimalAppendPreservesExactIntegralInputs() throws Exception {
        LogicalType integer = LogicalType.decimal(18, 0);
        LogicalType scaled = LogicalType.decimal(18, 2);
        LogicalType wide = LogicalType.decimal(38, 0);
        long exact = 9_007_199_254_740_993L;
        BigInteger large = new BigInteger("18446744073709551617");
        DataChunk chunk = new DataChunk(2, List.of(integer, scaled, wide), List.of(
                new DecodedVector.ObjectVec(integer, new Object[]{exact, -exact}),
                new DecodedVector.ObjectVec(scaled, new Object[]{BigInteger.valueOf(exact), BigInteger.valueOf(-exact)}),
                new DecodedVector.ObjectVec(wide, new Object[]{large, large})));
        try (QuackConnection c = connect(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE jdbc_it_append_exact (v DECIMAL(18,0), scaled DECIMAL(18,2), wide DECIMAL(38,0))");
            c.session().appendChunk("main", "jdbc_it_append_exact", chunk);
            try (ResultSet rs = s.executeQuery("SELECT * FROM jdbc_it_append_exact ORDER BY v DESC")) {
                for (long expected : new long[]{exact, -exact}) {
                    assertTrue(rs.next());
                    assertEquals(BigDecimal.valueOf(expected), rs.getBigDecimal(1));
                    assertEquals(BigDecimal.valueOf(expected).setScale(2), rs.getBigDecimal(2));
                    assertEquals(new BigDecimal(large), rs.getBigDecimal(3));
                }
                assertFalse(rs.next());
            }
        }
    }

    @Test
    void signedHugeIntsAndWideDecimalsAppendExactly() throws Exception {
        List<BigInteger> hugeValues = Arrays.asList(BigInteger.ONE.shiftLeft(127).negate(),
                BigInteger.valueOf(-1), BigInteger.ZERO, BigInteger.ONE.shiftLeft(63),
                BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE),
                BigInteger.ONE.shiftLeft(127).subtract(BigInteger.ONE), null);
        try (QuackConnection c = connect(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE jdbc_it_append_huge (i INTEGER, v HUGEINT)");
            LogicalType indexType = LogicalType.of(LogicalTypeId.INTEGER);
            LogicalType hugeType = LogicalType.of(LogicalTypeId.HUGEINT);
            c.session().appendChunk("main", "jdbc_it_append_huge", new DataChunk(hugeValues.size(),
                    List.of(indexType, hugeType), List.of(
                            new DecodedVector.IntVec(indexType, new int[]{0, 1, 2, 3, 4, 5, 6}, null),
                            new DecodedVector.ObjectVec(hugeType, hugeValues.toArray()))));
            try (ResultSet rs = s.executeQuery("SELECT v::VARCHAR, v FROM jdbc_it_append_huge ORDER BY i")) {
                for (BigInteger value : hugeValues) {
                    assertTrue(rs.next());
                    assertEquals(value == null ? null : value.toString(), rs.getString(1));
                    assertEquals(value, rs.getObject(2));
                }
                assertFalse(rs.next());
            }

            List<LogicalType> types = new ArrayList<>();
            List<DecodedVector> columns = new ArrayList<>();
            List<BigDecimal> maxima = new ArrayList<>();
            StringBuilder ddl = new StringBuilder("CREATE TABLE jdbc_it_append_wide_decimals (i INTEGER");
            types.add(indexType);
            columns.add(new DecodedVector.IntVec(indexType, new int[]{0, 1, 2}, null));
            for (int width : new int[]{19, 38}) {
                for (int scale : new int[]{0, 2}) {
                    LogicalType type = LogicalType.decimal(width, scale);
                    BigDecimal max = new BigDecimal(BigInteger.TEN.pow(width).subtract(BigInteger.ONE), scale);
                    ddl.append(", d").append(maxima.size()).append(" DECIMAL(")
                            .append(width).append(',').append(scale).append(')');
                    types.add(type);
                    columns.add(new DecodedVector.ObjectVec(type, new Object[]{max, max.negate(), null}));
                    maxima.add(max);
                }
            }
            s.execute(ddl.append(')').toString());
            c.session().appendChunk("main", "jdbc_it_append_wide_decimals", new DataChunk(3, types, columns));
            try (ResultSet rs = s.executeQuery("SELECT * FROM jdbc_it_append_wide_decimals ORDER BY i")) {
                for (int row = 0; row < 3; row++) {
                    assertTrue(rs.next());
                    for (int col = 0; col < maxima.size(); col++) {
                        BigDecimal expected = row == 0 ? maxima.get(col) : row == 1 ? maxima.get(col).negate() : null;
                        assertEquals(expected, rs.getBigDecimal(col + 2));
                    }
                }
                assertFalse(rs.next());
            }
        }
    }

    @Test
    void unsignedBigIntsAppendAndReadBackWithoutSignedNarrowing() throws Exception {
        List<BigInteger> values = Arrays.asList(BigInteger.ZERO, BigInteger.valueOf(Long.MAX_VALUE),
                BigInteger.ONE.shiftLeft(63), BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE), null);
        LogicalType indexType = LogicalType.of(LogicalTypeId.INTEGER);
        LogicalType type = LogicalType.of(LogicalTypeId.UBIGINT);
        try (QuackConnection c = connect(); Statement s = c.createStatement()) {
            s.execute("CREATE TABLE jdbc_it_append_ubigint (i INTEGER, v UBIGINT)");
            c.session().appendChunk("main", "jdbc_it_append_ubigint", new DataChunk(values.size(),
                    List.of(indexType, type), List.of(
                            new DecodedVector.IntVec(indexType, new int[]{0, 1, 2, 3, 4}, null),
                            new DecodedVector.ObjectVec(type, values.toArray()))));
            try (ResultSet rs = s.executeQuery("SELECT v, v::VARCHAR FROM jdbc_it_append_ubigint ORDER BY i")) {
                for (BigInteger value : values) {
                    assertTrue(rs.next());
                    assertEquals(value, rs.getObject(1));
                    assertEquals(value == null, rs.wasNull());
                    assertEquals(value, rs.getObject(1, BigInteger.class));
                    assertEquals(value == null ? null : new BigDecimal(value), rs.getBigDecimal(1));
                    assertEquals(value == null ? null : value.toString(), rs.getString(1));
                    assertEquals(rs.getString(2), rs.getString(1));
                }
                assertFalse(rs.next());
            }
        }
    }
}

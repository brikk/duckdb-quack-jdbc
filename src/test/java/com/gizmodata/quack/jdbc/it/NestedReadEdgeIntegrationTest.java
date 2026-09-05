package com.gizmodata.quack.jdbc.it;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIf;

import java.math.BigInteger;
import java.sql.Array;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Struct;
import java.sql.Types;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.gizmodata.quack.jdbc.it.QuackIntegrationTest#duckdbAvailable")
public class NestedReadEdgeIntegrationTest {

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
    void emptyListDecodesToEmptyArray() throws Exception {
        try (Connection c = connect();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT []::INTEGER[] AS a")) {
            assertTrue(rs.next());
            Array array = rs.getArray("a");
            assertNotNull(array);
            assertEquals(0, ((Object[]) array.getArray()).length);
        }
    }

    @Test
    void listWithNullElementPreservesNull() throws Exception {
        try (Connection c = connect();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT [1, NULL, 3]::INTEGER[] AS a")) {
            assertTrue(rs.next());
            Object[] elements = (Object[]) rs.getArray("a").getArray();
            assertEquals(3, elements.length);
            assertEquals(1, ((Number) elements[0]).intValue());
            assertNull(elements[1]);
            assertEquals(3, ((Number) elements[2]).intValue());
        }
    }

    @Test
    void nestedListWithEmptyInnerDecodes() throws Exception {
        try (Connection c = connect();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT [[], [1, 2]]::INTEGER[][] AS a")) {
            assertTrue(rs.next());
            Object[] outer = (Object[]) rs.getArray("a").getArray();
            assertEquals(2, outer.length);
            assertEquals(0, ((java.util.List<?>) outer[0]).size());
            assertEquals(2, ((java.util.List<?>) outer[1]).size());
        }
    }

    @Test
    void multiRowChunkWithNullAndNonNullLists() throws Exception {
        try (Connection c = connect();
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(
                     "SELECT * FROM (VALUES (1, [10, 20]), (2, NULL), (3, [])) t(id, arr) ORDER BY id")) {
            assertTrue(rs.next());
            assertEquals(2, ((Object[]) rs.getArray("arr").getArray()).length);
            assertTrue(rs.next());
            assertNull(rs.getArray("arr"));
            assertTrue(rs.wasNull());
            assertTrue(rs.next());
            assertEquals(0, ((Object[]) rs.getArray("arr").getArray()).length);
        }
    }

    @Test
    void unsignedBigIntegersStayExactInsideNestedValues() throws Exception {
        BigInteger max = BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE);
        BigInteger high = BigInteger.ONE.shiftLeft(63);
        try (Connection c = connect(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(
                "SELECT [0, 9223372036854775808, 18446744073709551615, NULL]::UBIGINT[] AS a, "
                        + "[0, 18446744073709551615, NULL]::UBIGINT[3] AS fixed, "
                        + "{'v': 18446744073709551615::UBIGINT, 'missing': NULL::UBIGINT} AS s, "
                        + "MAP {18446744073709551615::UBIGINT: 9223372036854775808::UBIGINT} AS m, "
                        + "[[18446744073709551615::UBIGINT, NULL::UBIGINT], []] AS nested")) {
            assertTrue(rs.next());
            Array array = rs.getArray("a");
            assertEquals(Types.OTHER, array.getBaseType());
            assertEquals("UBIGINT", array.getBaseTypeName());
            assertArrayEquals(new Object[]{BigInteger.ZERO, high, max, null}, (Object[]) array.getArray());
            assertArrayEquals(new Object[]{BigInteger.ZERO, max, null}, (Object[]) rs.getArray("fixed").getArray());
            assertArrayEquals(new Object[]{max, null}, ((Struct) rs.getObject("s")).getAttributes());
            assertEquals(Map.of(max, high), rs.getObject("m"));
            assertArrayEquals(new Object[]{Arrays.asList(max, null), List.of()},
                    (Object[]) rs.getArray("nested").getArray());
        }
    }

    @Test
    void nullTemporalScalarsAndMixedRowsPreserveValidity() throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            for (String type : new String[]{"DATE", "TIME", "TIME_NS", "TIMETZ", "TIMESTAMP_S",
                    "TIMESTAMP_MS", "TIMESTAMP", "TIMESTAMP_NS", "TIMESTAMPTZ"}) {
                try (ResultSet rs = s.executeQuery("SELECT NULL::" + type + " AS v")) {
                    assertTrue(rs.next(), type);
                    assertNull(rs.getObject(1), type);
                    assertTrue(rs.wasNull(), type);
                    assertNull(rs.getString(1), type);
                    assertTrue(rs.wasNull(), type);
                    assertFalse(rs.next(), type);
                }
            }
            for (String[] sample : new String[][]{{"TIME", "12:34:56.123456"},
                    {"TIME_NS", "12:34:56.123456789"}, {"TIMESTAMP_S", "2026-09-05 12:34:56"}}) {
                String type = sample[0];
                Object value = type.equals("TIMESTAMP_S")
                        ? LocalDateTime.parse(sample[1].replace(' ', 'T')) : LocalTime.parse(sample[1]);
                String literal = "'" + sample[1] + "'::" + type;
                String sql = "SELECT v FROM (VALUES (0, NULL::" + type + "), (1, " + literal
                        + "), (2, NULL), (3, " + literal + ")) t(i,v) ORDER BY i";
                try (ResultSet rs = s.executeQuery(sql)) {
                    for (int row = 0; row < 4; row++) {
                        assertTrue(rs.next());
                        assertEquals(row % 2 == 0 ? null : value, rs.getObject(1), type);
                        assertEquals(row % 2 == 0, rs.wasNull());
                        if (row % 2 == 0) {
                            if (type.equals("TIMESTAMP_S")) assertNull(rs.getTimestamp(1));
                            else assertNull(rs.getTime(1));
                            assertTrue(rs.wasNull());
                        }
                    }
                    assertFalse(rs.next());
                }
            }
        }
    }

    @Test
    void nullTemporalChildrenSurviveNestedVectors() throws Exception {
        try (Connection c = connect(); Statement s = c.createStatement()) {
            for (String[] sample : new String[][]{{"TIME", "12:34:56.123456"},
                    {"TIME_NS", "12:34:56.123456789"}, {"TIMESTAMP_S", "2026-09-05 12:34:56"}}) {
                String type = sample[0];
                Object value = type.equals("TIMESTAMP_S")
                        ? LocalDateTime.parse(sample[1].replace(' ', 'T')) : LocalTime.parse(sample[1]);
                String literal = "'" + sample[1] + "'::" + type;
                try (ResultSet rs = s.executeQuery("SELECT [NULL, " + literal + "] AS a, [" + literal
                        + ", NULL]::" + type + "[2] AS fixed, {'v': " + literal + ", 'missing': NULL::" + type
                        + "} AS s, NULL::STRUCT(v " + type + ") AS null_struct, [NULL::" + type
                        + "[], [NULL, " + literal + "]] AS nested, MAP {1: " + literal + ", 2: NULL::" + type + "} AS m")) {
                    assertTrue(rs.next());
                    assertArrayEquals(new Object[]{null, value}, (Object[]) rs.getArray("a").getArray(), type);
                    assertArrayEquals(new Object[]{value, null}, (Object[]) rs.getArray("fixed").getArray(), type);
                    assertArrayEquals(new Object[]{value, null}, ((Struct) rs.getObject("s")).getAttributes(), type);
                    assertNull(rs.getObject("null_struct"));
                    assertTrue(rs.wasNull());
                    assertArrayEquals(new Object[]{null, Arrays.asList(null, value)},
                            (Object[]) rs.getArray("nested").getArray(), type);
                    Map<?, ?> map = (Map<?, ?>) rs.getObject("m");
                    assertEquals(value, map.get(1), type);
                    assertTrue(map.containsKey(2));
                    assertNull(map.get(2));
                    assertFalse(rs.next());
                }
            }
        }
    }
}

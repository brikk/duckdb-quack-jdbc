package com.gizmodata.quack.jdbc.it;

import com.gizmodata.quack.jdbc.message.IntervalValue;
import com.gizmodata.quack.jdbc.sql.QuackArray;
import com.gizmodata.quack.jdbc.sql.QuackStruct;
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
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.StringJoiner;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.gizmodata.quack.jdbc.it.QuackIntegrationTest#duckdbAvailable")
public class ColumnClassMetadataIntegrationTest {

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

    @Test
    void nestedAndTimetzClassesMatchObjectsAcrossNullRows() throws Exception {
        assertColumnClasses(
                new Column("[1, 2]::INTEGER[]", QuackArray.class),
                new Column("[3, 4]::INTEGER[2]", QuackArray.class),
                new Column("row(5, 'tuple')", QuackStruct.class),
                new Column("{'x': 6, 'y': 'named'}", QuackStruct.class),
                new Column("MAP {1: 'a', 2: 'b'}", LinkedHashMap.class),
                new Column("'12:34:56.123456+05:30'::TIMETZ", Long.class));
    }

    @Test
    void scalarClassesRemainConsistentAcrossNullRows() throws Exception {
        assertColumnClasses(
                new Column("TRUE", Boolean.class),
                new Column("127::TINYINT", Byte.class),
                new Column("32767::SMALLINT", Short.class),
                new Column("2147483647::INTEGER", Integer.class),
                new Column("9223372036854775807::BIGINT", Long.class),
                new Column("255::UTINYINT", Short.class),
                new Column("65535::USMALLINT", Integer.class),
                new Column("4294967295::UINTEGER", Long.class),
                new Column("18446744073709551615::UBIGINT", BigInteger.class),
                new Column("170141183460469231731687303715884105727::HUGEINT", BigInteger.class),
                new Column("340282366920938463463374607431768211455::UHUGEINT", BigInteger.class),
                new Column("1.5::FLOAT", Float.class),
                new Column("2.5::DOUBLE", Double.class),
                new Column("12.34::DECIMAL(4,2)", BigDecimal.class),
                new Column("12345.67::DECIMAL(9,2)", BigDecimal.class),
                new Column("1234567890.12::DECIMAL(18,2)", BigDecimal.class),
                new Column("12345678901234567890.12::DECIMAL(38,2)", BigDecimal.class),
                new Column("'hello'::VARCHAR", String.class),
                new Column("'c'::CHAR", String.class),
                new Column("DATE '2026-09-06'", LocalDate.class),
                new Column("TIME '12:34:56.123456'", LocalTime.class),
                new Column("'12:34:56.123456789'::TIME_NS", LocalTime.class),
                new Column("'2026-09-06 12:34:56'::TIMESTAMP_S", LocalDateTime.class),
                new Column("'2026-09-06 12:34:56.123'::TIMESTAMP_MS", LocalDateTime.class),
                new Column("TIMESTAMP '2026-09-06 12:34:56.123456'", LocalDateTime.class),
                new Column("'2026-09-06 12:34:56.123456789'::TIMESTAMP_NS", LocalDateTime.class),
                new Column("'2026-09-06 12:34:56.123456+05:30'::TIMESTAMPTZ", OffsetDateTime.class),
                new Column("'550e8400-e29b-41d4-a716-446655440000'::UUID", UUID.class),
                new Column("'hello'::BLOB", byte[].class),
                new Column("'red'::ENUM('red', 'blue')", String.class),
                new Column("INTERVAL '1 month 2 days 3 seconds'", IntervalValue.class));
    }

    private record Column(String expression, Class<?> javaClass) {}

    private void assertColumnClasses(Column... columns) throws Exception {
        // A leading and trailing typed NULL ensure class metadata does not depend on row values.
        StringJoiner names = new StringJoiner(", ");
        StringJoiner values = new StringJoiner(", ");
        StringJoiner nulls = new StringJoiner(", ");
        for (int index = 0; index < columns.length; index++) {
            names.add("c" + index);
            values.add(columns[index].expression());
            nulls.add("NULL");
        }
        String sql = "SELECT " + names + " FROM (VALUES (0, " + nulls + "), (1, " + values
                + "), (2, " + nulls + ")) t(i, " + names + ") ORDER BY i";
        try (Connection c = DriverManager.getConnection(server.jdbcUrl());
             Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            ResultSetMetaData metadata = rs.getMetaData();
            assertEquals(columns.length, metadata.getColumnCount());
            for (int index = 0; index < columns.length; index++) {
                assertEquals(columns[index].javaClass().getName(), metadata.getColumnClassName(index + 1),
                        columns[index].expression());
            }
            for (int row = 0; row < 3; row++) {
                assertTrue(rs.next());
                for (int index = 0; index < columns.length; index++) {
                    Column column = columns[index];
                    String context = column.expression() + ", row " + row;
                    String className = rs.getMetaData().getColumnClassName(index + 1);
                    assertEquals(column.javaClass().getName(), className, context);
                    Object value = rs.getObject(index + 1);
                    assertEquals(row != 1, rs.wasNull(), context);
                    if (row == 1) {
                        assertNotNull(value, context);
                        assertEquals(column.javaClass(), value.getClass(), context);
                        assertTrue(Class.forName(className).isInstance(value), context);
                    } else {
                        assertNull(value, context);
                    }
                }
            }
            assertFalse(rs.next());
        }
    }
}

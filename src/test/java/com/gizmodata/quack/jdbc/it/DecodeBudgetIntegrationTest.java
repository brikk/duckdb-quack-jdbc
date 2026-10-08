package com.gizmodata.quack.jdbc.it;

import com.gizmodata.quack.jdbc.message.QuackMessage;
import com.gizmodata.quack.jdbc.sql.QuackDriver;
import com.gizmodata.quack.jdbc.transport.QuackHttpTransport;
import com.gizmodata.quack.jdbc.transport.QuackTransport;
import com.gizmodata.quack.jdbc.sql.QuackConnection;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.gizmodata.quack.jdbc.it.QuackIntegrationTest#duckdbAvailable")
class DecodeBudgetIntegrationTest {
    private static final int ROWS = 65_537;
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

    private Connection connect(AtomicInteger fetches) throws SQLException {
        Connection connection = new QuackDriver().connect(server.jdbcUrl(), new Properties(), uri -> {
            QuackHttpTransport transport = QuackHttpTransport.from(uri);
            return new QuackTransport() {
                @Override public QuackMessage send(QuackMessage request) {
                    if (request instanceof QuackMessage.FetchRequest) fetches.incrementAndGet();
                    return transport.send(request);
                }

                @Override public void setProtocolVersion(long version) {
                    transport.setProtocolVersion(version);
                }
            };
        });
        if (((QuackConnection) connection).session().protocolVersion() == 1) {
            try (Statement s = connection.createStatement(); ResultSet rs = s.executeQuery(
                    "SELECT current_setting('quack_fetch_batch_chunks')")) {
                assertTrue(rs.next());
                assertEquals(12, rs.getInt(1));
            }
        } else {
            try (Statement s = connection.createStatement()) {
                s.execute("SET quack_target_batch_bytes=65536");
            }
        }
        fetches.set(0);
        return connection;
    }

    @Test
    void qk01CombinedDecimalScalarsAndArraysFitDefaultBudget() throws Exception {
        String[] types = {"DECIMAL(4,2)", "DECIMAL(9,4)", "DECIMAL(18,6)", "DECIMAL(38,9)"};
        String[] maxima = {"99.99", "99999.9999", "999999999999.999999", "99999999999999999999999999999.999999999"};
        List<String> columns = new ArrayList<>();
        BigDecimal[] expectedMax = new BigDecimal[types.length];
        for (int k = 0; k < types.length; k++) {
            String value = "(CASE i%4 WHEN 0 THEN '" + maxima[k] + "' WHEN 1 THEN '-" + maxima[k]
                    + "' WHEN 2 THEN '0' WHEN 3 THEN NULL END)::" + types[k];
            columns.add(value);
            columns.add("[" + value + ", NULL]::" + types[k] + "[2]");
            expectedMax[k] = new BigDecimal(maxima[k]);
        }
        AtomicInteger fetches = new AtomicInteger();
        try (Connection c = connect(fetches); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(
                "SELECT i," + String.join(",", columns) + " FROM range(" + ROWS + ") t(i)")) {
            int row = 0;
            while (rs.next()) {
                assertEquals(row, rs.getLong(1));
                for (int k = 0; k < types.length; k++) {
                    BigDecimal expected = switch (row % 4) {
                        case 0 -> expectedMax[k];
                        case 1 -> expectedMax[k].negate();
                        case 2 -> BigDecimal.ZERO.setScale(expectedMax[k].scale());
                        default -> null;
                    };
                    assertEquals(expected, rs.getBigDecimal(2 + 2 * k));
                    assertEquals(expected == null, rs.wasNull());
                    java.sql.Array array = rs.getArray(3 + 2 * k);
                    try { assertArrayEquals(new Object[]{expected, null}, (Object[]) array.getArray()); }
                    finally { array.free(); }
                }
                row++;
            }
            assertEquals(ROWS, row);
            assertTrue(fetches.get() >= 2, "must read across actual FETCH requests");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void qk02WideTemporalScalarsAndChildrenFitDefaultBudget(boolean allNull) throws Exception {
        String[] types = {"DATE", "TIME", "TIME_NS", "TIMESTAMP_S", "TIMESTAMP_MS", "TIMESTAMP", "TIMESTAMP_NS", "TIMESTAMPTZ"};
        String[] text = {"2000-02-29", "23:59:59.999999", "23:59:59.999999999", "1969-12-31 23:59:59",
                "2000-02-29 12:34:56.789", "2000-02-29 12:34:56.123456", "2000-02-29 12:34:56.123456789", "2000-02-29 12:34:56.123456+00"};
        Object[] expected = {LocalDate.parse(text[0]), LocalTime.parse(text[1]), LocalTime.parse(text[2]),
                LocalDateTime.parse(text[3].replace(' ', 'T')), LocalDateTime.parse(text[4].replace(' ', 'T')),
                LocalDateTime.parse(text[5].replace(' ', 'T')), LocalDateTime.parse(text[6].replace(' ', 'T')),
                Instant.parse("2000-02-29T12:34:56.123456Z")};
        List<String> columns = new ArrayList<>();
        for (int k = 0; k < types.length; k++) {
            columns.add(allNull ? "NULL::" + types[k]
                    : "CASE WHEN i%3=0 THEN NULL ELSE '" + text[k] + "'::" + types[k] + " END");
        }
        columns.add("[NULL::TIME, CASE WHEN i%3=0 THEN NULL ELSE TIME '01:02:03.456789' END]");
        columns.add("[NULL::TIMESTAMP_S, CASE WHEN i%3=0 THEN NULL ELSE TIMESTAMP_S '1969-12-31 23:59:59' END]::TIMESTAMP_S[2]");
        AtomicInteger fetches = new AtomicInteger();
        try (Connection c = connect(fetches); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(
                "SELECT i," + String.join(",", columns) + " FROM range(" + ROWS + ") t(i)")) {
            int row = 0;
            while (rs.next()) {
                assertEquals(row, rs.getLong(1));
                for (int k = 0; k < types.length; k++) {
                    boolean isNull = allNull || row % 3 == 0;
                    Object actual = rs.getObject(k + 2);
                    assertEquals(isNull, rs.wasNull());
                    if (actual instanceof OffsetDateTime zoned) actual = zoned.toInstant();
                    assertEquals(isNull ? null : expected[k], actual, types[k]);
                    if (isNull) {
                        if (k == 0) assertNull(rs.getDate(k + 2));
                        else if (k <= 2) assertNull(rs.getTime(k + 2));
                        else assertNull(rs.getTimestamp(k + 2));
                        assertTrue(rs.wasNull());
                    }
                }
                for (int column = 10; column <= 11; column++) {
                    Object child = row % 3 == 0 ? null : column == 10
                            ? LocalTime.parse("01:02:03.456789") : LocalDateTime.parse("1969-12-31T23:59:59");
                    java.sql.Array array = rs.getArray(column);
                    try { assertArrayEquals(new Object[]{null, child}, (Object[]) array.getArray()); }
                    finally { array.free(); }
                }
                row++;
            }
            assertEquals(ROWS, row);
            assertTrue(fetches.get() >= 2, "must read across actual FETCH requests");
        }
    }
}

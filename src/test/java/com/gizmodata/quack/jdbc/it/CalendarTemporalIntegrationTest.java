package com.gizmodata.quack.jdbc.it;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.parallel.Isolated;

import java.sql.Connection;
import java.sql.Date;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.Calendar;
import java.util.Locale;
import java.util.SimpleTimeZone;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("integration")
@Isolated("Changes the JVM default timezone")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@EnabledIf("com.gizmodata.quack.jdbc.it.QuackIntegrationTest#duckdbAvailable")
public class CalendarTemporalIntegrationTest {
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
    void calendarSettersBindLocalFieldsWithoutLosingTimestampMicros() throws Exception {
        TimeZone original = TimeZone.getDefault();
        try (Connection c = DriverManager.getConnection(server.jdbcUrl());
             PreparedStatement ps = c.prepareStatement("SELECT ? AS d, ? AS t, ? AS ts")) {
            for (String defaultZone : new String[]{"UTC", "America/Los_Angeles", "Pacific/Apia"}) {
                TimeZone.setDefault(TimeZone.getTimeZone(defaultZone));
                for (TimeZone zone : new TimeZone[]{TimeZone.getTimeZone("Asia/Tokyo"),
                        new SimpleTimeZone(9 * 3600_000, "B11/custom"), new SimpleTimeZone(9 * 3600_000, "UTC")}) {
                    Calendar cal = Calendar.getInstance(zone);
                    Calendar saved = (Calendar) cal.clone();
                    Date date = new Date(Instant.parse("2025-12-31T20:00:00Z").toEpochMilli());
                    Time time = new Time(Instant.parse("1970-01-01T20:00:00.123Z").toEpochMilli());
                    Timestamp timestamp = Timestamp.from(Instant.parse("2026-01-01T00:00:00.123456789Z"));
                    ps.setDate(1, date, cal);
                    ps.setTime(2, time, cal);
                    ps.setTimestamp(3, timestamp, cal);
                    assertEquals(saved, cal);
                    assertEquals(Instant.parse("2025-12-31T20:00:00Z").toEpochMilli(), date.getTime());
                    assertEquals(Instant.parse("1970-01-01T20:00:00.123Z").toEpochMilli(), time.getTime());
                    assertEquals(Instant.parse("2026-01-01T00:00:00.123456789Z"), timestamp.toInstant());
                    // Parameters must capture the fields at bind time, not retain mutable inputs.
                    date.setTime(0);
                    time.setTime(0);
                    timestamp.setTime(0);
                    cal.setTimeZone(TimeZone.getTimeZone("UTC"));
                    try (ResultSet rs = ps.executeQuery()) {
                        assertTrue(rs.next());
                        assertEquals(LocalDate.of(2026, 1, 1), rs.getObject("d"));
                        assertEquals(LocalTime.parse("05:00:00.123"), rs.getObject("t"));
                        assertEquals(LocalDateTime.parse("2026-01-01T09:00:00.123456"), rs.getObject("ts"));
                    }
                }
                Calendar utc = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
                ps.setDate(1, new Date(Instant.parse("2011-12-30T00:00:00Z").toEpochMilli()), utc);
                ps.setTime(2, null, utc);
                ps.setTimestamp(3, Timestamp.from(Instant.parse("1969-12-31T23:59:59.999999Z")), utc);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(LocalDate.of(2011, 12, 30), rs.getObject(1));
                    assertNull(rs.getTime(2, utc));
                    assertTrue(rs.wasNull());
                    assertEquals(Instant.parse("1969-12-31T23:59:59.999999Z"), rs.getTimestamp(3, utc).toInstant());
                }
            }
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test
    void calendarTimestampBindingsUseTheOffsetAtTheValueInstant() throws Exception {
        String[][] cases = {
                {"2024-01-01T00:00:00.123456Z", "2023-12-31T16:00:00.123456"},
                {"2024-03-10T09:30:00.123456Z", "2024-03-10T01:30:00.123456"},
                {"2024-03-10T10:30:00.123456Z", "2024-03-10T03:30:00.123456"},
                {"2024-11-03T08:30:00.123456Z", "2024-11-03T01:30:00.123456"},
                {"2024-11-03T09:30:00.123456Z", "2024-11-03T01:30:00.123456"}
        };
        Calendar la = Calendar.getInstance(TimeZone.getTimeZone("America/Los_Angeles"));
        la.setTimeInMillis(0);
        try (Connection c = DriverManager.getConnection(server.jdbcUrl()); Statement s = c.createStatement();
             PreparedStatement ps = c.prepareStatement("SELECT ? AS v")) {
            for (String serverZone : new String[]{"UTC", "Asia/Tokyo"}) {
                s.execute("SET TimeZone = '" + serverZone + "'");
                for (String[] sample : cases) {
                    ps.setTimestamp(1, Timestamp.from(Instant.parse(sample[0])), la);
                    try (ResultSet rs = ps.executeQuery()) {
                        assertTrue(rs.next());
                        assertEquals(LocalDateTime.parse(sample[1]), rs.getObject(1));
                    }
                }
            }
        }
        assertEquals(0, la.getTimeInMillis());
    }

    @Test
    void calendarReadsCoverAllTimestampUnitsAndServerTimeZones() throws Exception {
        String[][] types = {{"TIMESTAMP_S", ""}, {"TIMESTAMP_MS", ".123"},
                {"TIMESTAMP", ".123456"}, {"TIMESTAMP_NS", ".123456789"}};
        Calendar la = Calendar.getInstance(TimeZone.getTimeZone("America/Los_Angeles"));
        try (Connection c = DriverManager.getConnection(server.jdbcUrl()); Statement s = c.createStatement()) {
            for (String serverZone : new String[]{"UTC", "Asia/Tokyo"}) {
                s.execute("SET TimeZone = '" + serverZone + "'");
                for (String[] type : types) {
                    try (ResultSet rs = s.executeQuery("SELECT '2024-01-02 03:04:05" + type[1] + "'::" + type[0] + " AS v")) {
                        assertTrue(rs.next());
                        assertEquals(Instant.parse("2024-01-02T11:04:05" + type[1] + "Z"), rs.getTimestamp("v", la).toInstant());
                    }
                }
                try (ResultSet rs = s.executeQuery("SELECT TIMESTAMPTZ '2024-11-03 08:30:00.123456+00' AS a, "
                        + "TIMESTAMPTZ '2024-11-03 09:30:00.123456+00' AS b, DATE '2024-01-02' AS d, TIME '00:15:00.123456' AS t")) {
                    assertTrue(rs.next());
                    assertEquals(Instant.parse("2024-11-03T08:30:00.123456Z"), rs.getTimestamp("a", la).toInstant());
                    assertEquals(Instant.parse("2024-11-03T09:30:00.123456Z"), rs.getTimestamp("b", la).toInstant());
                    assertEquals(Instant.parse("2024-01-02T08:00:00Z").toEpochMilli(), rs.getDate("d", la).getTime());
                    assertEquals(Instant.parse("1970-01-01T08:15:00.123Z").toEpochMilli(), rs.getTime("t", la).getTime());
                }
            }
        }
    }

    @Test
    void preCutoverTimestampsRoundTripWithDifferentCalendarSystems() throws Exception {
        try (Connection c = DriverManager.getConnection(server.jdbcUrl());
             PreparedStatement ps = c.prepareStatement("SELECT ? AS v")) {
            for (Locale locale : new Locale[]{Locale.ROOT, Locale.forLanguageTag("th-TH")}) {
                Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"), locale);
                for (String value : new String[]{"1500-01-01T03:04:05.123456Z", "2024-01-02T03:04:05.123456Z"}) {
                    Timestamp timestamp = Timestamp.from(Instant.parse(value));
                    ps.setTimestamp(1, timestamp, cal);
                    try (ResultSet rs = ps.executeQuery()) {
                        assertTrue(rs.next());
                        assertEquals(timestamp.toInstant(), rs.getTimestamp(1, cal).toInstant());
                    }
                }
            }
        }
    }

    @Test
    void nullCalendarsRetainDefaultBehaviorAndNullValues() throws Exception {
        Date date = Date.valueOf("2024-01-02");
        Time time = Time.valueOf("03:04:05");
        Timestamp timestamp = Timestamp.valueOf("2024-01-02 03:04:05.123456");
        try (Connection c = DriverManager.getConnection(server.jdbcUrl());
             PreparedStatement ps = c.prepareStatement("SELECT ? AS d, ? AS t, ? AS ts")) {
            for (boolean withCalendar : new boolean[]{false, true}) {
                if (withCalendar) {
                    ps.setDate(1, date, null);
                    ps.setTime(2, time, null);
                    ps.setTimestamp(3, timestamp, null);
                } else {
                    ps.setDate(1, date);
                    ps.setTime(2, time);
                    ps.setTimestamp(3, timestamp);
                }
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(date, rs.getDate(1, null));
                    assertEquals(time, rs.getTime(2, null));
                    assertEquals(timestamp, rs.getTimestamp(3, null));
                }
            }
            for (Calendar cal : new Calendar[]{null, Calendar.getInstance(TimeZone.getTimeZone("Asia/Tokyo"))}) {
                ps.setDate(1, null, cal);
                ps.setTime(2, null, cal);
                ps.setTimestamp(3, null, cal);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertNull(rs.getDate(1, cal));
                    assertTrue(rs.wasNull());
                    assertNull(rs.getTime(2, cal));
                    assertTrue(rs.wasNull());
                    assertNull(rs.getTimestamp(3, cal));
                    assertTrue(rs.wasNull());
                }
            }
        }
    }
}

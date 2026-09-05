package com.gizmodata.quack.jdbc.sql;

import com.gizmodata.quack.jdbc.codec.HugeIntParts;
import com.gizmodata.quack.jdbc.message.DataChunk;
import com.gizmodata.quack.jdbc.message.DecodedVector;
import com.gizmodata.quack.jdbc.message.MessageHeader;
import com.gizmodata.quack.jdbc.message.MessageType;
import com.gizmodata.quack.jdbc.message.QuackMessage;
import com.gizmodata.quack.jdbc.transport.QuackUri;
import com.gizmodata.quack.jdbc.type.LogicalType;
import com.gizmodata.quack.jdbc.type.LogicalTypeId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Isolated;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.SimpleTimeZone;
import java.util.TimeZone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Isolated("Changes the JVM default timezone")
class QuackResultSetConversionTest {
    @Test
    void integralValuesStayExactWhenReadAsBigDecimal() throws Exception {
        for (Number value : new Number[]{(byte) -128, (short) 32767, Integer.MAX_VALUE,
                9_007_199_254_740_993L, -9_007_199_254_740_993L, Long.MIN_VALUE, Long.MAX_VALUE,
                new BigInteger("18446744073709551617")}) {
            try (var rs = result(LogicalTypeId.BIGINT, value)) {
                assertTrue(rs.next());
                BigDecimal expected = new BigDecimal(value.toString());
                assertEquals(expected, rs.getBigDecimal(1));
                assertEquals(expected, rs.getObject(1, BigDecimal.class));
            }
        }
    }

    @Test
    void bigIntegerConversionDoesNotNarrowStringsThroughLong() throws Exception {
        for (String value : new String[]{"18446744073709551616", "-18446744073709551617",
                "18446744073709551616.75", "-18446744073709551616.75", "1E40"}) {
            try (var rs = result(LogicalTypeId.VARCHAR, value)) {
                assertTrue(rs.next());
                assertEquals(new BigDecimal(value).toBigInteger(), rs.getObject(1, BigInteger.class));
            }
        }
        for (Number value : new Number[]{Math.scalb(1.0, 60), -Math.scalb(1.0, 63),
                Math.scalb(1.0f, 60), 1e30, -1e30, 1.5, -1.5}) {
            try (var rs = result(LogicalTypeId.DOUBLE, value)) {
                assertTrue(rs.next());
                assertEquals(new BigDecimal(value.doubleValue()).toBigInteger(), rs.getObject(1, BigInteger.class));
            }
        }
        for (double value : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            try (var rs = result(LogicalTypeId.DOUBLE, value)) {
                assertTrue(rs.next());
                assertThrows(SQLException.class, () -> rs.getObject(1, BigInteger.class));
            }
        }
    }

    @Test
    void decimalScaleAndNullConversionsArePreserved() throws Exception {
        BigDecimal decimal = new BigDecimal("123.4500");
        try (var rs = result(LogicalTypeId.DECIMAL, decimal)) {
            assertTrue(rs.next());
            assertEquals(decimal, rs.getBigDecimal(1));
        }
        try (var rs = result(LogicalTypeId.BIGINT, null)) {
            assertTrue(rs.next());
            assertNull(rs.getBigDecimal(1));
            assertTrue(rs.wasNull());
            assertNull(rs.getObject(1, BigInteger.class));
            assertTrue(rs.wasNull());
        }
    }

    @Test
    void calendarTimestampReadsDoNotPassThroughTheDefaultZone() throws Exception {
        String[][] cases = {
                {"2024-01-02T03:04:05.123456", "America/Los_Angeles", "2024-01-02T11:04:05.123456Z"},
                {"2024-07-02T03:04:05.123456789", "America/Los_Angeles", "2024-07-02T10:04:05.123456789Z"},
                {"2024-03-10T02:30:00.000001", "Asia/Tokyo", "2024-03-09T17:30:00.000001Z"},
                {"2024-03-10T03:30:00", "America/Los_Angeles", "2024-03-10T10:30:00Z"},
                {"2024-11-03T02:30:00", "America/Los_Angeles", "2024-11-03T10:30:00Z"},
                {"1969-12-31T23:59:59.999999999", "UTC", "1969-12-31T23:59:59.999999999Z"},
                {"2011-12-30T12:00:00", "UTC", "2011-12-30T12:00:00Z"}
        };
        TimeZone original = TimeZone.getDefault();
        try {
            for (String defaultZone : new String[]{"UTC", "America/Los_Angeles", "Pacific/Apia"}) {
                TimeZone.setDefault(TimeZone.getTimeZone(defaultZone));
                for (String[] sample : cases) {
                    Calendar cal = Calendar.getInstance(TimeZone.getTimeZone(sample[1]));
                    Calendar saved = (Calendar) cal.clone();
                    LocalDateTime value = LocalDateTime.parse(sample[0]);
                    // VARCHAR must not use Timestamp.valueOf either: it also interprets the JVM zone.
                    for (Object raw : new Object[]{value, sample[0].replace('T', ' ')}) {
                        try (var rs = result(raw instanceof String ? LogicalTypeId.VARCHAR : LogicalTypeId.TIMESTAMP_NS, raw)) {
                            assertTrue(rs.next());
                            assertEquals(Instant.parse(sample[2]), rs.getTimestamp(1, cal).toInstant(), defaultZone);
                            assertEquals(value.getNano(), rs.getTimestamp("value", cal).getNanos());
                            assertFalse(rs.wasNull());
                            assertEquals(saved, cal);
                        }
                    }
                }
            }
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test
    void calendarControlsDstResolutionWithoutBeingMutated() throws Exception {
        String[][] cases = {
                {"America/Los_Angeles", "2024-03-10T02:30:00", "2024-03-10T10:30:00Z", "gap"},
                {"America/Los_Angeles", "2024-11-03T01:30:00", "2024-11-03T09:30:00Z", "overlap"},
                {"Australia/Lord_Howe", "2024-10-06T02:15:00", "2024-10-05T15:45:00Z", "gap"},
                {"Australia/Lord_Howe", "2024-04-07T01:45:00", "2024-04-06T15:15:00Z", "overlap"}
        };
        for (String[] sample : cases) {
            Calendar cal = Calendar.getInstance(TimeZone.getTimeZone(sample[0]));
            cal.setTimeInMillis(123456789);
            Calendar saved = (Calendar) cal.clone();
            try (var rs = result(LogicalTypeId.TIMESTAMP, LocalDateTime.parse(sample[1]))) {
                assertTrue(rs.next());
                assertEquals(Instant.parse(sample[2]), rs.getTimestamp(1, cal).toInstant());
                assertEquals(saved, cal);
                cal.setLenient(false);
                saved.setLenient(false);
                if (sample[3].equals("gap")) {
                    assertThrows(SQLException.class, () -> rs.getTimestamp(1, cal));
                } else {
                    assertEquals(Instant.parse(sample[2]), rs.getTimestamp(1, cal).toInstant());
                }
                assertEquals(saved, cal);
            }
        }
    }

    @Test
    void calendarDateAndTimeReadsUseMidnightAndTheEpochDate() throws Exception {
        String[][] cases = {
                {"Asia/Tokyo", "2024-01-01T15:00:00Z", "1969-12-31T15:15:00.123Z"},
                {"America/Los_Angeles", "2024-01-02T08:00:00Z", "1970-01-01T08:15:00.123Z"},
                {"Asia/Kathmandu", "2024-01-01T18:15:00Z", "1969-12-31T18:45:00.123Z"}
        };
        for (String[] sample : cases) {
            Calendar cal = Calendar.getInstance(TimeZone.getTimeZone(sample[0]));
            cal.setTimeInMillis(Instant.parse("2024-07-01T00:00:00.999Z").toEpochMilli());
            Calendar saved = (Calendar) cal.clone();
            for (Object raw : new Object[]{LocalDate.of(2024, 1, 2), LocalDateTime.of(2024, 1, 2, 12, 34), "2024-01-02"}) {
                try (var rs = result(LogicalTypeId.DATE, raw)) {
                    assertTrue(rs.next());
                    assertEquals(Instant.parse(sample[1]).toEpochMilli(), rs.getDate(1, cal).getTime());
                    assertEquals(rs.getDate(1, cal), rs.getDate("value", cal));
                    if (raw instanceof LocalDate) {
                        assertEquals(Instant.parse(sample[1]), rs.getTimestamp(1, cal).toInstant());
                    }
                }
            }
            for (Object raw : new Object[]{LocalTime.parse("00:15:00.123456789"),
                    LocalDateTime.parse("2024-07-01T00:15:00.123456789"), "00:15:00.123456789"}) {
                try (var rs = result(LogicalTypeId.TIME_NS, raw)) {
                    assertTrue(rs.next());
                    assertEquals(Instant.parse(sample[2]).toEpochMilli(), rs.getTime(1, cal).getTime());
                    assertEquals(rs.getTime(1, cal), rs.getTime("value", cal));
                }
            }
            assertEquals(saved, cal);
        }
    }

    @Test
    void calendarDateReadsHandleMidnightTransitionsAndZonedDates() throws Exception {
        String[][] cases = {
                {"America/Sao_Paulo", "2018-11-04", "2018-11-04T03:00:00Z"},
                {"Pacific/Apia", "2011-12-30", "2011-12-30T10:00:00Z"}
        };
        for (String[] sample : cases) {
            Calendar cal = Calendar.getInstance(TimeZone.getTimeZone(sample[0]));
            try (var rs = result(LogicalTypeId.DATE, LocalDate.parse(sample[1]))) {
                assertTrue(rs.next());
                assertEquals(Instant.parse(sample[2]).toEpochMilli(), rs.getDate(1, cal).getTime());
                assertEquals(Instant.parse(sample[2]), rs.getTimestamp(1, cal).toInstant());
                cal.setLenient(false);
                assertThrows(SQLException.class, () -> rs.getDate(1, cal));
                assertThrows(SQLException.class, () -> rs.getTimestamp(1, cal));
            }
        }
        Calendar la = Calendar.getInstance(TimeZone.getTimeZone("America/Los_Angeles"));
        try (var rs = result(LogicalTypeId.TIMESTAMP_TZ, OffsetDateTime.parse("2024-01-02T01:00:00Z"))) {
            assertTrue(rs.next());
            assertEquals(Instant.parse("2024-01-01T08:00:00Z").toEpochMilli(), rs.getDate(1, la).getTime());
        }
        // Preserve the legacy JDBC string grammar without an intermediate default-zone conversion.
        try (var rs = result(LogicalTypeId.VARCHAR, " 2024-1-2 3:4:5.123456 ")) {
            assertTrue(rs.next());
            assertEquals(Instant.parse("2024-01-02T11:04:05.123456Z"), rs.getTimestamp(1, la).toInstant());
        }
    }

    @Test
    void calendarSystemsAndCutoversDoNotChangeSqlFields() throws Exception {
        for (Locale locale : new Locale[]{Locale.ROOT, Locale.forLanguageTag("th-TH"),
                Locale.forLanguageTag("ja-JP-u-ca-japanese")}) {
            Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"), locale);
            Calendar saved = (Calendar) cal.clone();
            for (String value : new String[]{"2024-01-02T03:04:05.123456", "1500-01-01T03:04:05.123456"}) {
                try (var rs = result(LogicalTypeId.TIMESTAMP, LocalDateTime.parse(value))) {
                    assertTrue(rs.next());
                    assertEquals(Instant.parse(value + "Z"), rs.getTimestamp(1, cal).toInstant());
                    assertEquals(Instant.parse(value.substring(0, 10) + "T00:00:00Z").toEpochMilli(), rs.getDate(1, cal).getTime());
                    assertEquals(Instant.parse("1970-01-01T03:04:05.123Z").toEpochMilli(), rs.getTime(1, cal).getTime());
                }
            }
            assertEquals(saved, cal);
        }
    }

    @Test
    void invalidCalendarStringsAreRejectedBeforeNormalization() throws Exception {
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        cal.setLenient(false);
        for (String value : new String[]{"2024-02-30", "2024-13-01"}) {
            try (var rs = result(LogicalTypeId.VARCHAR, value)) {
                assertTrue(rs.next());
                assertThrows(SQLException.class, () -> rs.getDate(1, cal));
            }
            try (var rs = result(LogicalTypeId.VARCHAR, value + " 03:04:05")) {
                assertTrue(rs.next());
                assertThrows(SQLException.class, () -> rs.getTimestamp(1, cal));
            }
        }
        for (String value : new String[]{"24:00:00", "03:60:00"}) {
            try (var rs = result(LogicalTypeId.VARCHAR, value)) {
                assertTrue(rs.next());
                assertThrows(SQLException.class, () -> rs.getTime(1, cal));
            }
            try (var rs = result(LogicalTypeId.VARCHAR, "2024-01-02 " + value)) {
                assertTrue(rs.next());
                assertThrows(SQLException.class, () -> rs.getTimestamp(1, cal));
            }
        }
    }

    @Test
    void calendarTimestampWithTimeZoneReadsPreserveTheInstant() throws Exception {
        TimeZone original = TimeZone.getDefault();
        try {
            for (String defaultZone : new String[]{"UTC", "America/Los_Angeles"}) {
                TimeZone.setDefault(TimeZone.getTimeZone(defaultZone));
                for (String instant : new String[]{"2024-11-03T08:30:00.123456Z", "2024-11-03T09:30:00.123456Z"}) {
                    try (var rs = result(LogicalTypeId.TIMESTAMP_TZ, OffsetDateTime.parse(instant))) {
                        assertTrue(rs.next());
                        for (String zone : new String[]{"UTC", "Asia/Tokyo", "America/Los_Angeles"}) {
                            Calendar cal = Calendar.getInstance(TimeZone.getTimeZone(zone));
                            assertEquals(Instant.parse(instant), rs.getTimestamp(1, cal).toInstant());
                            assertEquals(rs.getTimestamp(1), rs.getTimestamp("value", cal));
                        }
                    }
                }
            }
        } finally {
            TimeZone.setDefault(original);
        }
    }

    @Test
    void calendarReadsHonorCustomTimeZoneRulesAndHandleNulls() throws Exception {
        for (String id : new String[]{"B11/custom", "UTC"}) {
            Calendar cal = Calendar.getInstance(new SimpleTimeZone(9 * 3600_000, id));
            try (var rs = result(LogicalTypeId.TIMESTAMP, LocalDateTime.of(2026, 1, 1, 9, 0))) {
                assertTrue(rs.next());
                assertEquals(Instant.parse("2026-01-01T00:00:00Z"), rs.getTimestamp(1, cal).toInstant());
                assertEquals(rs.getTimestamp(1), rs.getTimestamp(1, null));
                assertEquals(rs.getDate(1), rs.getDate("value", null));
                assertEquals(rs.getTime(1), rs.getTime("value", null));
            }
            try (var rs = result(LogicalTypeId.TIMESTAMP, null)) {
                assertTrue(rs.next());
                assertNull(rs.getTimestamp(1, cal));
                assertTrue(rs.wasNull());
                assertNull(rs.getTimestamp("value", cal));
                assertTrue(rs.wasNull());
                assertNull(rs.getDate(1, cal));
                assertTrue(rs.wasNull());
                assertNull(rs.getDate("value", cal));
                assertTrue(rs.wasNull());
                assertNull(rs.getTime(1, cal));
                assertTrue(rs.wasNull());
                assertNull(rs.getTime("value", cal));
                assertTrue(rs.wasNull());
            }
        }
    }

    private static QuackResultSet result(LogicalTypeId id, Object value) {
        LogicalType type = LogicalType.of(id);
        DataChunk chunk = new DataChunk(1, List.of(type), List.of(
                new DecodedVector.ObjectVec(type, new Object[]{value})));
        QuackSession session = new QuackSession(QuackUri.parse("jdbc:quack://example.test"), request -> {
            throw new AssertionError("Unexpected I/O");
        });
        var response = new QuackMessage.PrepareResponse(MessageHeader.of(MessageType.PREPARE_RESPONSE),
                List.of(type), List.of("value"), false, List.of(chunk), new HugeIntParts(0, 0));
        return new QuackResultSet(null, new QuackSession.Cursor(session, response));
    }
}

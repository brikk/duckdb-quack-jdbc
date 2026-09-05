package com.gizmodata.quack.jdbc.sql;

import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Calendar;
import java.util.GregorianCalendar;

final class CalendarConversion {
    private CalendarConversion() {
    }

    static LocalDateTime toLocalDateTime(Instant instant, Calendar cal) {
        // Use the actual TimeZone rules, not its ID: custom IDs and modified offsets are valid.
        int offset = cal.getTimeZone().getOffset(instant.toEpochMilli());
        return LocalDateTime.ofInstant(instant.plusMillis(offset), ZoneOffset.UTC);
    }

    static Instant toInstant(LocalDateTime value, Calendar cal) throws SQLException {
        // DuckDB local fields are proleptic Gregorian, regardless of the caller's calendar system.
        GregorianCalendar copy = new GregorianCalendar(cal.getTimeZone());
        copy.setGregorianChange(new java.util.Date(Long.MIN_VALUE));
        copy.setLenient(cal.isLenient());
        copy.clear();
        int year = value.getYear();
        copy.set(Calendar.ERA, year <= 0 ? GregorianCalendar.BC : GregorianCalendar.AD);
        copy.set(year <= 0 ? 1 - year : year, value.getMonthValue() - 1, value.getDayOfMonth(),
                value.getHour(), value.getMinute(), value.getSecond());
        copy.set(Calendar.MILLISECOND, value.getNano() / 1_000_000);
        try {
            // Calendar resolves DST and enforces leniency; restore the sub-millisecond fraction.
            return Instant.ofEpochMilli(copy.getTimeInMillis()).plusNanos(value.getNano() % 1_000_000);
        } catch (IllegalArgumentException e) {
            throw new SQLException("Cannot interpret " + value + " using the supplied Calendar", e);
        }
    }
}

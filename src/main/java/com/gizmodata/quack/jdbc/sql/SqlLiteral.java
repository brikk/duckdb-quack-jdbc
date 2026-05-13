package com.gizmodata.quack.jdbc.sql;

import com.gizmodata.quack.jdbc.message.IntervalValue;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Date;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Renders Java values as DuckDB SQL literals for client-side parameter
 * interpolation. Quack PREPARE_REQUEST does not yet carry bind parameters,
 * so PreparedStatement parameters are interpolated into the SQL text.
 *
 * <p>This is the riskiest piece of the driver from a SQL-injection
 * perspective. Strings are escaped via single-quote doubling; binary
 * blobs as {@code '\xNN\xNN...'::BLOB}. Untrusted SQL must still be
 * vetted by the caller.
 */
public final class SqlLiteral {

    private static final DateTimeFormatter TIMESTAMP_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");
    private static final DateTimeFormatter TIMESTAMP_TZ_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSSxxx");

    private SqlLiteral() {
    }

    public static String render(Object value) {
        if (value == null) return "NULL";
        if (value instanceof Boolean b) return b.toString();
        if (value instanceof Byte b) return b.toString();
        if (value instanceof Short s) return s.toString();
        if (value instanceof Integer i) return i.toString();
        if (value instanceof Long l) return l.toString();
        if (value instanceof Float f) return renderDouble(f.doubleValue());
        if (value instanceof Double d) return renderDouble(d);
        if (value instanceof BigDecimal bd) return bd.toPlainString();
        if (value instanceof BigInteger bi) return bi.toString();
        if (value instanceof String s) return "'" + s.replace("'", "''") + "'";
        if (value instanceof byte[] bytes) return blobLiteral(bytes);
        if (value instanceof LocalDate d) return "DATE '" + d + "'";
        if (value instanceof LocalTime t) return "TIME '" + t + "'";
        if (value instanceof LocalDateTime dt) return "TIMESTAMP '" + dt.format(TIMESTAMP_FORMAT) + "'";
        if (value instanceof OffsetDateTime odt) return "TIMESTAMPTZ '" + odt.format(TIMESTAMP_TZ_FORMAT) + "'";
        if (value instanceof Date sd) return "DATE '" + sd.toLocalDate() + "'";
        if (value instanceof Time st) return "TIME '" + st.toLocalTime() + "'";
        if (value instanceof Timestamp sts) return "TIMESTAMP '" + sts.toLocalDateTime().format(TIMESTAMP_FORMAT) + "'";
        if (value instanceof UUID u) return "UUID '" + u + "'";
        if (value instanceof IntervalValue iv) {
            return "INTERVAL '" + iv.months() + " months " + iv.days() + " days "
                    + iv.micros() + " microseconds'";
        }
        return "'" + value.toString().replace("'", "''") + "'";
    }

    private static String renderDouble(double d) {
        if (Double.isNaN(d)) return "'NaN'::DOUBLE";
        if (Double.isInfinite(d)) return d > 0 ? "'Infinity'::DOUBLE" : "'-Infinity'::DOUBLE";
        return Double.toString(d);
    }

    private static String blobLiteral(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 4 + 8);
        sb.append("'");
        for (byte b : bytes) {
            sb.append("\\x");
            sb.append(String.format("%02X", b & 0xFF));
        }
        sb.append("'::BLOB");
        return sb.toString();
    }
}

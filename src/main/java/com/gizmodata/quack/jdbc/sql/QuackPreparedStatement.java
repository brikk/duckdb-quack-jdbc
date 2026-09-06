package com.gizmodata.quack.jdbc.sql;

import com.gizmodata.quack.jdbc.QuackProtocolException;
import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLType;
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public class QuackPreparedStatement extends QuackStatement implements PreparedStatement {

    private static final Object UNBOUND = new Object();

    private final QuackConnection connection;
    private final String sql;
    private final List<Integer> markerPositions;
    private final List<Object> parameters;
    private final List<List<Object>> paramBatch = new ArrayList<>();

    public QuackPreparedStatement(QuackConnection connection, String sql) {
        super(connection);
        this.connection = connection;
        this.sql = sql;
        this.markerPositions = parameterPositions(sql);
        this.parameters = new ArrayList<>(Collections.nCopies(markerPositions.size(), UNBOUND));
    }

    private void setParam(int index, Object value) throws SQLException {
        checkParameterIndex(index);
        parameters.set(index - 1, value);
    }

    private void checkParameterIndex(int index) throws SQLException {
        checkOpen();
        if (index < 1 || index > markerPositions.size()) {
            throw new SQLException("Parameter index out of range: " + index + " (count: " + markerPositions.size() + ")");
        }
    }

    private void validateParameters(List<Object> params) throws SQLException {
        checkOpen();
        for (int i = 0; i < params.size(); i++) {
            if (params.get(i) == UNBOUND) throw new SQLException("Parameter " + (i + 1) + " is not bound");
        }
    }

    private String interpolate(List<Object> params) throws SQLException {
        validateParameters(params);
        StringBuilder out = new StringBuilder(sql.length() + 32);
        int start = 0;
        for (int p = 0; p < markerPositions.size(); p++) {
            int position = markerPositions.get(p);
            out.append(sql, start, position);
            // Prevent token merging (e.g. --1) and newline escape-string continuation.
            String literal = SqlLiteral.render(params.get(p));
            out.append("/**/");
            // Unary minus must bind before a following cast or exponentiation.
            if (literal.startsWith("-")) out.append('(').append(literal).append(')');
            else out.append(literal);
            out.append("/**/");
            start = position + 1;
        }
        return out.append(sql, start, sql.length()).toString();
    }

    private String interpolate() throws SQLException {
        return interpolate(parameters);
    }

    private String interpolateWithDefaults() throws SQLException {
        List<Object> padded = new ArrayList<>(parameters);
        // Metadata probes must not turn missing execution bindings into bound NULLs.
        padded.replaceAll(value -> value == UNBOUND ? null : value);
        return interpolate(padded);
    }

    private static List<Integer> parameterPositions(String sql) {
        // DuckDB preprocesses these before lexing, using different quoting rules.
        // Reject ambiguous SQL rather than risk substituting inside a server-side literal.
        if (SqlLiteral.hasNormalizedWhitespace(sql)) {
            throw new QuackProtocolException("DuckDB-normalized Unicode whitespace is not supported in prepared SQL; "
                    + "use ASCII whitespace or bind the text as a parameter");
        }
        List<Integer> positions = new ArrayList<>();
        int length = sql.length();
        for (int i = 0; i < length;) {
            char c = sql.charAt(i);
            char next = i + 1 < length ? sql.charAt(i + 1) : 0;
            if (c == '-' && next == '-') {
                i += 2;
                while (i < length && sql.charAt(i) != '\n' && sql.charAt(i) != '\r') i++;
            } else if (c == '/' && next == '*') {
                int depth = 1;
                i += 2;
                while (i < length && depth > 0) {
                    if (sql.startsWith("/*", i)) {
                        depth++;
                        i += 2;
                    } else if (sql.startsWith("*/", i)) {
                        depth--;
                        i += 2;
                    } else {
                        i++;
                    }
                }
            } else if (c == '\'' || c == '"' || ((c == 'e' || c == 'E') && next == '\'')) {
                boolean escapes = c == 'e' || c == 'E';
                char quote = escapes ? '\'' : c;
                i += escapes ? 2 : 1;
                while (i < length) {
                    char quoted = sql.charAt(i++);
                    if (escapes && quoted == '\\' && i < length) {
                        i++;
                    } else if (quoted == quote) {
                        if (i < length && sql.charAt(i) == quote) {
                            i++;
                        } else {
                            int continuation = quote == '\'' ? stringContinuation(sql, i) : -1;
                            if (continuation < 0) break;
                            i = continuation + 1;
                        }
                    }
                }
            } else if (identifierStart(c)) {
                // Consume the full token so an embedded E or $tag$ is not a quote prefix.
                do { i++; } while (i < length && (identifierStart(sql.charAt(i))
                        || (sql.charAt(i) >= '0' && sql.charAt(i) <= '9') || sql.charAt(i) == '$'));
            } else if (c == '$') {
                int end = i + 1;
                if (end < length && identifierStart(sql.charAt(end))) {
                    do { end++; } while (end < length && (identifierStart(sql.charAt(end))
                            || (sql.charAt(end) >= '0' && sql.charAt(end) <= '9')));
                }
                if (end < length && sql.charAt(end) == '$') {
                    String delimiter = sql.substring(i, end + 1);
                    int close = sql.indexOf(delimiter, end + 1);
                    i = close < 0 ? length : close + delimiter.length();
                } else {
                    i++;
                }
            } else {
                if (c == '?') positions.add(i);
                i++;
            }
        }
        return positions;
    }

    private static boolean identifierStart(char c) {
        return c == '_' || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c >= 128;
    }

    private static int stringContinuation(String sql, int position) {
        boolean newline = false;
        while (position < sql.length()) {
            char c = sql.charAt(position);
            if (c == '\n' || c == '\r') {
                newline = true;
                position++;
            } else if (c == ' ' || c == '\t' || c == '\f') {
                position++;
            } else if (sql.startsWith("--", position)) {
                position += 2;
                while (position < sql.length() && sql.charAt(position) != '\n'
                        && sql.charAt(position) != '\r') position++;
            } else {
                return newline && c == '\'' ? position : -1;
            }
        }
        return -1;
    }

    @Override
    public ResultSet executeQuery() throws SQLException {
        checkOpen();
        resetExecutionState();
        return executeQuery(interpolate());
    }

    @Override
    public int executeUpdate() throws SQLException {
        checkOpen();
        resetExecutionState();
        return executeUpdate(interpolate());
    }

    @Override
    public boolean execute() throws SQLException {
        checkOpen();
        resetExecutionState();
        return execute(interpolate());
    }

    @Override
    public void addBatch() throws SQLException {
        validateParameters(parameters);
        // Snapshot the current parameter binding for later replay.
        paramBatch.add(new ArrayList<>(parameters));
    }

    @Override
    public void addBatch(String sql) throws SQLException {
        // JDBC contract: PreparedStatement disallows the SQL-taking variant.
        throw new SQLException("PreparedStatement.addBatch(String) is not allowed");
    }

    @Override
    public void clearBatch() {
        paramBatch.clear();
    }

    @Override
    public int[] executeBatch() throws SQLException {
        checkOpen();
        resetExecutionState();
        int[] counts = new int[paramBatch.size()];
        for (int i = 0; i < paramBatch.size(); i++) {
            try {
                resetExecutionState();
                counts[i] = executeUpdate(interpolate(paramBatch.get(i)));
            } catch (SQLException e) {
                paramBatch.clear();
                throw new java.sql.BatchUpdateException(e.getMessage(), e.getSQLState(), e.getErrorCode(),
                        Arrays.copyOf(counts, i), e);
            }
        }
        paramBatch.clear();
        return counts;
    }

    @Override
    public void clearParameters() {
        Collections.fill(parameters, UNBOUND);
    }

    @Override
    public ResultSetMetaData getMetaData() throws SQLException {
        // Run "SELECT * FROM (<sql>) LIMIT 0" so we get the schema without
        // materializing any rows. For non-SELECT prepared statements
        // (INSERT/UPDATE/DDL/etc.) the wrap will fail server-side and we
        // return null per JDBC contract.
        try {
            String wrapped = "SELECT * FROM (" + interpolateWithDefaults() + ") LIMIT 0";
            try (QuackSession.Cursor cursor = connection.session().cursor(wrapped)) {
                return new QuackResultSetMetaData(cursor.columnNames(), cursor.columnTypes());
            }
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    @Override
    public ParameterMetaData getParameterMetaData() {
        return new QuackParameterMetaData(markerPositions.size());
    }

    @Override public void setNull(int i, int sqlType) throws SQLException { setParam(i, null); }
    @Override public void setNull(int i, int sqlType, String typeName) throws SQLException { setParam(i, null); }
    @Override public void setBoolean(int i, boolean x) throws SQLException { setParam(i, x); }
    @Override public void setByte(int i, byte x) throws SQLException { setParam(i, x); }
    @Override public void setShort(int i, short x) throws SQLException { setParam(i, x); }
    @Override public void setInt(int i, int x) throws SQLException { setParam(i, x); }
    @Override public void setLong(int i, long x) throws SQLException { setParam(i, x); }
    @Override public void setFloat(int i, float x) throws SQLException { setParam(i, x); }
    @Override public void setDouble(int i, double x) throws SQLException { setParam(i, x); }
    @Override public void setBigDecimal(int i, BigDecimal x) throws SQLException { setParam(i, x); }
    @Override public void setString(int i, String x) throws SQLException { setParam(i, x); }
    @Override public void setBytes(int i, byte[] x) throws SQLException { setParam(i, x); }
    @Override public void setDate(int i, Date x) throws SQLException { setParam(i, x == null ? null : x.toLocalDate()); }
    @Override public void setTime(int i, Time x) throws SQLException { setParam(i, x == null ? null : x.toLocalTime()); }
    @Override public void setTimestamp(int i, Timestamp x) throws SQLException { setParam(i, x == null ? null : x.toLocalDateTime()); }
    @Override public void setDate(int i, Date x, Calendar cal) throws SQLException {
        if (cal == null || x == null) setDate(i, x);
        else setParam(i, CalendarConversion.toLocalDateTime(Instant.ofEpochMilli(x.getTime()), cal).toLocalDate());
    }
    @Override public void setTime(int i, Time x, Calendar cal) throws SQLException {
        if (cal == null || x == null) setTime(i, x);
        else setParam(i, CalendarConversion.toLocalDateTime(Instant.ofEpochMilli(x.getTime()), cal).toLocalTime());
    }
    @Override public void setTimestamp(int i, Timestamp x, Calendar cal) throws SQLException {
        if (cal == null || x == null) setTimestamp(i, x);
        else setParam(i, CalendarConversion.toLocalDateTime(x.toInstant(), cal));
    }
    @Override public void setObject(int i, Object x) throws SQLException { setParam(i, x); }
    @Override public void setObject(int i, Object x, int targetSqlType) throws SQLException { setParam(i, x); }
    @Override public void setObject(int i, Object x, int targetSqlType, int scaleOrLength) throws SQLException { setParam(i, x); }
    @Override public void setObject(int i, Object x, SQLType targetSqlType) throws SQLException { setParam(i, x); }
    @Override public void setObject(int i, Object x, SQLType targetSqlType, int scaleOrLength) throws SQLException { setParam(i, x); }
    @Override public void setURL(int i, URL x) throws SQLException { setParam(i, x == null ? null : x.toString()); }
    @Override public void setRowId(int i, RowId x) throws SQLException { throw notSupported("RowId"); }
    @Override public void setNString(int i, String x) throws SQLException { setString(i, x); }
    @Override public void setNCharacterStream(int i, Reader r, long len) throws SQLException { throw notSupported("NCharacterStream"); }
    @Override public void setNCharacterStream(int i, Reader r) throws SQLException { throw notSupported("NCharacterStream"); }
    @Override public void setNClob(int i, NClob x) throws SQLException { throw notSupported("NClob"); }
    @Override public void setNClob(int i, Reader r, long len) throws SQLException { throw notSupported("NClob"); }
    @Override public void setNClob(int i, Reader r) throws SQLException { throw notSupported("NClob"); }
    @Override public void setClob(int i, Clob x) throws SQLException { throw notSupported("Clob"); }
    @Override public void setClob(int i, Reader r, long len) throws SQLException { throw notSupported("Clob"); }
    @Override public void setClob(int i, Reader r) throws SQLException { throw notSupported("Clob"); }
    @Override public void setBlob(int i, Blob x) throws SQLException { throw notSupported("Blob"); }
    @Override public void setBlob(int i, InputStream s, long len) throws SQLException { throw notSupported("Blob"); }
    @Override public void setBlob(int i, InputStream s) throws SQLException { throw notSupported("Blob"); }
    @Override public void setSQLXML(int i, SQLXML x) throws SQLException { throw notSupported("SQLXML"); }
    @Override public void setRef(int i, Ref x) throws SQLException { throw notSupported("Ref"); }
    @Override public void setArray(int i, Array x) throws SQLException {
        checkParameterIndex(i);
        setParam(i, x == null ? null : x.getArray());
    }
    @Override public void setAsciiStream(int i, InputStream s, int len) throws SQLException { setAsciiStream(i, s, (long) len); }
    @Override public void setAsciiStream(int i, InputStream s, long len) throws SQLException {
        checkParameterIndex(i);
        try { setParam(i, s == null ? null : new String(s.readNBytes((int) len), StandardCharsets.US_ASCII)); }
        catch (java.io.IOException e) { throw new SQLException(e); }
    }
    @Override public void setAsciiStream(int i, InputStream s) throws SQLException {
        checkParameterIndex(i);
        try { setParam(i, s == null ? null : new String(s.readAllBytes(), StandardCharsets.US_ASCII)); }
        catch (java.io.IOException e) { throw new SQLException(e); }
    }
    @Override public void setUnicodeStream(int i, InputStream s, int len) throws SQLException { throw notSupported("UnicodeStream"); }
    @Override public void setBinaryStream(int i, InputStream s, int len) throws SQLException { setBinaryStream(i, s, (long) len); }
    @Override public void setBinaryStream(int i, InputStream s, long len) throws SQLException {
        checkParameterIndex(i);
        try { setParam(i, s == null ? null : s.readNBytes((int) len)); }
        catch (java.io.IOException e) { throw new SQLException(e); }
    }
    @Override public void setBinaryStream(int i, InputStream s) throws SQLException {
        checkParameterIndex(i);
        try { setParam(i, s == null ? null : s.readAllBytes()); }
        catch (java.io.IOException e) { throw new SQLException(e); }
    }
    @Override public void setCharacterStream(int i, Reader r, int len) throws SQLException { setCharacterStream(i, r, (long) len); }
    @Override public void setCharacterStream(int i, Reader r, long len) throws SQLException {
        checkParameterIndex(i);
        try { setParam(i, r == null ? null : readReader(r, (int) len)); }
        catch (java.io.IOException e) { throw new SQLException(e); }
    }
    @Override public void setCharacterStream(int i, Reader r) throws SQLException {
        checkParameterIndex(i);
        try { setParam(i, r == null ? null : readReader(r, -1)); }
        catch (java.io.IOException e) { throw new SQLException(e); }
    }

    private static String readReader(Reader r, int max) throws java.io.IOException {
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[1024];
        int total = 0;
        int n;
        while ((n = r.read(buf)) > 0) {
            int take = max < 0 ? n : Math.min(n, max - total);
            sb.append(buf, 0, take);
            total += take;
            if (max >= 0 && total >= max) break;
        }
        return sb.toString();
    }

    @SuppressWarnings("unused")
    private static class Markers {
        // referenced types for static analyzers
        static final Class<?>[] T = {Calendar.class, LocalDate.class, LocalTime.class,
                LocalDateTime.class, OffsetDateTime.class, BigInteger.class, UUID.class,
                SQLFeatureNotSupportedException.class};
    }
}

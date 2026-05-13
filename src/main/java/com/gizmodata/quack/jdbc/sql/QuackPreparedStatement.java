package com.gizmodata.quack.jdbc.sql;

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
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.UUID;

public class QuackPreparedStatement extends QuackStatement implements PreparedStatement {

    private final QuackConnection connection;
    private final String sql;
    private final int markerCount;
    private final List<Object> parameters = new ArrayList<>();
    private final List<List<Object>> paramBatch = new ArrayList<>();

    public QuackPreparedStatement(QuackConnection connection, String sql) {
        super(connection);
        this.connection = connection;
        this.sql = sql;
        this.markerCount = countMarkers(sql);
    }

    private void setParam(int index, Object value) throws SQLException {
        if (index < 1) {
            throw new SQLException("Parameter index must be >= 1: " + index);
        }
        while (parameters.size() < index) parameters.add(null);
        parameters.set(index - 1, value);
    }

    private String interpolate(List<Object> params) throws SQLException {
        StringBuilder out = new StringBuilder(sql.length() + 32);
        int paramIndex = 0;
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '\'' && !inDouble) {
                inSingle = !inSingle;
                out.append(c);
            } else if (c == '"' && !inSingle) {
                inDouble = !inDouble;
                out.append(c);
            } else if (c == '?' && !inSingle && !inDouble) {
                if (paramIndex >= params.size()) {
                    throw new SQLException("Not enough parameters bound for SQL: " + sql);
                }
                out.append(SqlLiteral.render(params.get(paramIndex++)));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private String interpolate() throws SQLException {
        return interpolate(parameters);
    }

    private String interpolateWithDefaults() throws SQLException {
        List<Object> padded = new ArrayList<>(parameters);
        while (padded.size() < markerCount) padded.add(null);
        return interpolate(padded);
    }

    static int countMarkers(String sql) {
        int count = 0;
        boolean inSingle = false, inDouble = false;
        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '\'' && !inDouble) inSingle = !inSingle;
            else if (c == '"' && !inSingle) inDouble = !inDouble;
            else if (c == '?' && !inSingle && !inDouble) count++;
        }
        return count;
    }

    @Override
    public ResultSet executeQuery() throws SQLException {
        return executeQuery(interpolate());
    }

    @Override
    public int executeUpdate() throws SQLException {
        return executeUpdate(interpolate());
    }

    @Override
    public boolean execute() throws SQLException {
        return execute(interpolate());
    }

    @Override
    public void addBatch() throws SQLException {
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
        int[] counts = new int[paramBatch.size()];
        for (int i = 0; i < paramBatch.size(); i++) {
            try {
                counts[i] = executeUpdate(interpolate(paramBatch.get(i)));
            } catch (SQLException e) {
                counts[i] = java.sql.Statement.EXECUTE_FAILED;
                paramBatch.clear();
                throw new java.sql.BatchUpdateException(e.getMessage(), counts, e);
            }
        }
        paramBatch.clear();
        return counts;
    }

    @Override
    public void clearParameters() {
        parameters.clear();
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
        return new QuackParameterMetaData(markerCount);
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
    @Override public void setDate(int i, Date x, Calendar cal) throws SQLException { setDate(i, x); }
    @Override public void setTime(int i, Time x, Calendar cal) throws SQLException { setTime(i, x); }
    @Override public void setTimestamp(int i, Timestamp x, Calendar cal) throws SQLException { setTimestamp(i, x); }
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
    @Override public void setArray(int i, Array x) throws SQLException { setParam(i, x == null ? null : x.getArray()); }
    @Override public void setAsciiStream(int i, InputStream s, int len) throws SQLException { setAsciiStream(i, s, (long) len); }
    @Override public void setAsciiStream(int i, InputStream s, long len) throws SQLException {
        try { setParam(i, s == null ? null : new String(s.readNBytes((int) len), StandardCharsets.US_ASCII)); }
        catch (java.io.IOException e) { throw new SQLException(e); }
    }
    @Override public void setAsciiStream(int i, InputStream s) throws SQLException {
        try { setParam(i, s == null ? null : new String(s.readAllBytes(), StandardCharsets.US_ASCII)); }
        catch (java.io.IOException e) { throw new SQLException(e); }
    }
    @Override public void setUnicodeStream(int i, InputStream s, int len) throws SQLException { throw notSupported("UnicodeStream"); }
    @Override public void setBinaryStream(int i, InputStream s, int len) throws SQLException { setBinaryStream(i, s, (long) len); }
    @Override public void setBinaryStream(int i, InputStream s, long len) throws SQLException {
        try { setParam(i, s == null ? null : s.readNBytes((int) len)); }
        catch (java.io.IOException e) { throw new SQLException(e); }
    }
    @Override public void setBinaryStream(int i, InputStream s) throws SQLException {
        try { setParam(i, s == null ? null : s.readAllBytes()); }
        catch (java.io.IOException e) { throw new SQLException(e); }
    }
    @Override public void setCharacterStream(int i, Reader r, int len) throws SQLException { setCharacterStream(i, r, (long) len); }
    @Override public void setCharacterStream(int i, Reader r, long len) throws SQLException {
        try { setParam(i, r == null ? null : readReader(r, (int) len)); }
        catch (java.io.IOException e) { throw new SQLException(e); }
    }
    @Override public void setCharacterStream(int i, Reader r) throws SQLException {
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

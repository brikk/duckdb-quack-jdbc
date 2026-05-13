package com.gizmodata.quack.jdbc.sql;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.SQLWarning;
import java.sql.SQLXML;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.Calendar;
import java.util.Map;

/** Base ResultSet that throws for every method; concrete subclasses override the ones we support. */
public abstract class SkeletalResultSet implements ResultSet {

    private static SQLException notSupported(String what) {
        return new SQLFeatureNotSupportedException(what + " is not supported by quack-jdbc");
    }

    @Override public SQLWarning getWarnings() { return null; }
    @Override public void clearWarnings() { /* no-op */ }
    @Override public String getCursorName() throws SQLException { throw notSupported("cursor name"); }
    @Override public int getFetchDirection() { return FETCH_FORWARD; }
    @Override public void setFetchDirection(int direction) { /* no-op */ }
    @Override public int getFetchSize() { return 0; }
    @Override public void setFetchSize(int rows) { /* no-op */ }
    @Override public int getType() { return TYPE_FORWARD_ONLY; }
    @Override public int getConcurrency() { return CONCUR_READ_ONLY; }
    @Override public int getHoldability() { return CLOSE_CURSORS_AT_COMMIT; }
    @Override public boolean isBeforeFirst() throws SQLException { throw notSupported("isBeforeFirst"); }
    @Override public boolean isAfterLast() throws SQLException { throw notSupported("isAfterLast"); }
    @Override public boolean isFirst() throws SQLException { throw notSupported("isFirst"); }
    @Override public boolean isLast() throws SQLException { throw notSupported("isLast"); }
    @Override public void beforeFirst() throws SQLException { throw notSupported("beforeFirst"); }
    @Override public void afterLast() throws SQLException { throw notSupported("afterLast"); }
    @Override public boolean first() throws SQLException { throw notSupported("first"); }
    @Override public boolean last() throws SQLException { throw notSupported("last"); }
    @Override public boolean absolute(int row) throws SQLException { throw notSupported("absolute"); }
    @Override public boolean relative(int rows) throws SQLException { throw notSupported("relative"); }
    @Override public boolean previous() throws SQLException { throw notSupported("previous"); }
    @Override public boolean rowUpdated() { return false; }
    @Override public boolean rowInserted() { return false; }
    @Override public boolean rowDeleted() { return false; }

    @Override public void updateNull(int columnIndex) throws SQLException { throw notSupported("updates"); }
    @Override public void updateBoolean(int columnIndex, boolean x) throws SQLException { throw notSupported("updates"); }
    @Override public void updateByte(int columnIndex, byte x) throws SQLException { throw notSupported("updates"); }
    @Override public void updateShort(int columnIndex, short x) throws SQLException { throw notSupported("updates"); }
    @Override public void updateInt(int columnIndex, int x) throws SQLException { throw notSupported("updates"); }
    @Override public void updateLong(int columnIndex, long x) throws SQLException { throw notSupported("updates"); }
    @Override public void updateFloat(int columnIndex, float x) throws SQLException { throw notSupported("updates"); }
    @Override public void updateDouble(int columnIndex, double x) throws SQLException { throw notSupported("updates"); }
    @Override public void updateBigDecimal(int columnIndex, BigDecimal x) throws SQLException { throw notSupported("updates"); }
    @Override public void updateString(int columnIndex, String x) throws SQLException { throw notSupported("updates"); }
    @Override public void updateBytes(int columnIndex, byte[] x) throws SQLException { throw notSupported("updates"); }
    @Override public void updateDate(int columnIndex, Date x) throws SQLException { throw notSupported("updates"); }
    @Override public void updateTime(int columnIndex, Time x) throws SQLException { throw notSupported("updates"); }
    @Override public void updateTimestamp(int columnIndex, Timestamp x) throws SQLException { throw notSupported("updates"); }
    @Override public void updateAsciiStream(int columnIndex, InputStream x, int length) throws SQLException { throw notSupported("updates"); }
    @Override public void updateBinaryStream(int columnIndex, InputStream x, int length) throws SQLException { throw notSupported("updates"); }
    @Override public void updateCharacterStream(int columnIndex, Reader x, int length) throws SQLException { throw notSupported("updates"); }
    @Override public void updateObject(int columnIndex, Object x, int scaleOrLength) throws SQLException { throw notSupported("updates"); }
    @Override public void updateObject(int columnIndex, Object x) throws SQLException { throw notSupported("updates"); }
    @Override public void updateNull(String columnLabel) throws SQLException { throw notSupported("updates"); }
    @Override public void updateBoolean(String columnLabel, boolean x) throws SQLException { throw notSupported("updates"); }
    @Override public void updateByte(String columnLabel, byte x) throws SQLException { throw notSupported("updates"); }
    @Override public void updateShort(String columnLabel, short x) throws SQLException { throw notSupported("updates"); }
    @Override public void updateInt(String columnLabel, int x) throws SQLException { throw notSupported("updates"); }
    @Override public void updateLong(String columnLabel, long x) throws SQLException { throw notSupported("updates"); }
    @Override public void updateFloat(String columnLabel, float x) throws SQLException { throw notSupported("updates"); }
    @Override public void updateDouble(String columnLabel, double x) throws SQLException { throw notSupported("updates"); }
    @Override public void updateBigDecimal(String columnLabel, BigDecimal x) throws SQLException { throw notSupported("updates"); }
    @Override public void updateString(String columnLabel, String x) throws SQLException { throw notSupported("updates"); }
    @Override public void updateBytes(String columnLabel, byte[] x) throws SQLException { throw notSupported("updates"); }
    @Override public void updateDate(String columnLabel, Date x) throws SQLException { throw notSupported("updates"); }
    @Override public void updateTime(String columnLabel, Time x) throws SQLException { throw notSupported("updates"); }
    @Override public void updateTimestamp(String columnLabel, Timestamp x) throws SQLException { throw notSupported("updates"); }
    @Override public void updateAsciiStream(String columnLabel, InputStream x, int length) throws SQLException { throw notSupported("updates"); }
    @Override public void updateBinaryStream(String columnLabel, InputStream x, int length) throws SQLException { throw notSupported("updates"); }
    @Override public void updateCharacterStream(String columnLabel, Reader reader, int length) throws SQLException { throw notSupported("updates"); }
    @Override public void updateObject(String columnLabel, Object x, int scaleOrLength) throws SQLException { throw notSupported("updates"); }
    @Override public void updateObject(String columnLabel, Object x) throws SQLException { throw notSupported("updates"); }
    @Override public void insertRow() throws SQLException { throw notSupported("updates"); }
    @Override public void updateRow() throws SQLException { throw notSupported("updates"); }
    @Override public void deleteRow() throws SQLException { throw notSupported("updates"); }
    @Override public void refreshRow() throws SQLException { throw notSupported("updates"); }
    @Override public void cancelRowUpdates() throws SQLException { throw notSupported("updates"); }
    @Override public void moveToInsertRow() throws SQLException { throw notSupported("updates"); }
    @Override public void moveToCurrentRow() throws SQLException { throw notSupported("updates"); }
    @Override public Statement getStatement() throws SQLException { throw notSupported("getStatement"); }
    @Override public Object getObject(int columnIndex, Map<String, Class<?>> map) throws SQLException { return getObject(columnIndex); }
    @Override public Ref getRef(int columnIndex) throws SQLException { throw notSupported("Ref"); }
    @Override public Blob getBlob(int columnIndex) throws SQLException { throw notSupported("Blob"); }
    @Override public Clob getClob(int columnIndex) throws SQLException { throw notSupported("Clob"); }
    @Override public Array getArray(int columnIndex) throws SQLException { throw notSupported("Array"); }
    @Override public Object getObject(String columnLabel, Map<String, Class<?>> map) throws SQLException { return getObject(columnLabel); }
    @Override public Ref getRef(String columnLabel) throws SQLException { throw notSupported("Ref"); }
    @Override public Blob getBlob(String columnLabel) throws SQLException { throw notSupported("Blob"); }
    @Override public Clob getClob(String columnLabel) throws SQLException { throw notSupported("Clob"); }
    @Override public Array getArray(String columnLabel) throws SQLException { throw notSupported("Array"); }
    @Override public Date getDate(int columnIndex, Calendar cal) throws SQLException { return getDate(columnIndex); }
    @Override public Date getDate(String columnLabel, Calendar cal) throws SQLException { return getDate(columnLabel); }
    @Override public Time getTime(int columnIndex, Calendar cal) throws SQLException { return getTime(columnIndex); }
    @Override public Time getTime(String columnLabel, Calendar cal) throws SQLException { return getTime(columnLabel); }
    @Override public Timestamp getTimestamp(int columnIndex, Calendar cal) throws SQLException { return getTimestamp(columnIndex); }
    @Override public Timestamp getTimestamp(String columnLabel, Calendar cal) throws SQLException { return getTimestamp(columnLabel); }
    @Override public URL getURL(int columnIndex) throws SQLException {
        String s = getString(columnIndex);
        try { return s == null ? null : new java.net.URI(s).toURL(); }
        catch (Exception e) { throw new SQLException("Invalid URL: " + s, e); }
    }
    @Override public URL getURL(String columnLabel) throws SQLException {
        String s = getString(columnLabel);
        try { return s == null ? null : new java.net.URI(s).toURL(); }
        catch (Exception e) { throw new SQLException("Invalid URL: " + s, e); }
    }
    @Override public void updateRef(int columnIndex, Ref x) throws SQLException { throw notSupported("updates"); }
    @Override public void updateRef(String columnLabel, Ref x) throws SQLException { throw notSupported("updates"); }
    @Override public void updateBlob(int columnIndex, Blob x) throws SQLException { throw notSupported("updates"); }
    @Override public void updateBlob(String columnLabel, Blob x) throws SQLException { throw notSupported("updates"); }
    @Override public void updateClob(int columnIndex, Clob x) throws SQLException { throw notSupported("updates"); }
    @Override public void updateClob(String columnLabel, Clob x) throws SQLException { throw notSupported("updates"); }
    @Override public void updateArray(int columnIndex, Array x) throws SQLException { throw notSupported("updates"); }
    @Override public void updateArray(String columnLabel, Array x) throws SQLException { throw notSupported("updates"); }
    @Override public RowId getRowId(int columnIndex) throws SQLException { throw notSupported("RowId"); }
    @Override public RowId getRowId(String columnLabel) throws SQLException { throw notSupported("RowId"); }
    @Override public void updateRowId(int columnIndex, RowId x) throws SQLException { throw notSupported("RowId"); }
    @Override public void updateRowId(String columnLabel, RowId x) throws SQLException { throw notSupported("RowId"); }
    @Override public void updateNString(int columnIndex, String nString) throws SQLException { throw notSupported("updates"); }
    @Override public void updateNString(String columnLabel, String nString) throws SQLException { throw notSupported("updates"); }
    @Override public void updateNClob(int columnIndex, NClob nClob) throws SQLException { throw notSupported("NClob"); }
    @Override public void updateNClob(String columnLabel, NClob nClob) throws SQLException { throw notSupported("NClob"); }
    @Override public NClob getNClob(int columnIndex) throws SQLException { throw notSupported("NClob"); }
    @Override public NClob getNClob(String columnLabel) throws SQLException { throw notSupported("NClob"); }
    @Override public SQLXML getSQLXML(int columnIndex) throws SQLException { throw notSupported("SQLXML"); }
    @Override public SQLXML getSQLXML(String columnLabel) throws SQLException { throw notSupported("SQLXML"); }
    @Override public void updateSQLXML(int columnIndex, SQLXML xmlObject) throws SQLException { throw notSupported("SQLXML"); }
    @Override public void updateSQLXML(String columnLabel, SQLXML xmlObject) throws SQLException { throw notSupported("SQLXML"); }
    @Override public String getNString(int columnIndex) throws SQLException { return getString(columnIndex); }
    @Override public String getNString(String columnLabel) throws SQLException { return getString(columnLabel); }
    @Override public Reader getNCharacterStream(int columnIndex) throws SQLException { return getCharacterStream(columnIndex); }
    @Override public Reader getNCharacterStream(String columnLabel) throws SQLException { return getCharacterStream(columnLabel); }
    @Override public void updateNCharacterStream(int columnIndex, Reader x, long length) throws SQLException { throw notSupported("updates"); }
    @Override public void updateNCharacterStream(String columnLabel, Reader x, long length) throws SQLException { throw notSupported("updates"); }
    @Override public void updateAsciiStream(int columnIndex, InputStream x, long length) throws SQLException { throw notSupported("updates"); }
    @Override public void updateBinaryStream(int columnIndex, InputStream x, long length) throws SQLException { throw notSupported("updates"); }
    @Override public void updateCharacterStream(int columnIndex, Reader x, long length) throws SQLException { throw notSupported("updates"); }
    @Override public void updateAsciiStream(String columnLabel, InputStream x, long length) throws SQLException { throw notSupported("updates"); }
    @Override public void updateBinaryStream(String columnLabel, InputStream x, long length) throws SQLException { throw notSupported("updates"); }
    @Override public void updateCharacterStream(String columnLabel, Reader reader, long length) throws SQLException { throw notSupported("updates"); }
    @Override public void updateBlob(int columnIndex, InputStream inputStream, long length) throws SQLException { throw notSupported("updates"); }
    @Override public void updateBlob(String columnLabel, InputStream inputStream, long length) throws SQLException { throw notSupported("updates"); }
    @Override public void updateClob(int columnIndex, Reader reader, long length) throws SQLException { throw notSupported("updates"); }
    @Override public void updateClob(String columnLabel, Reader reader, long length) throws SQLException { throw notSupported("updates"); }
    @Override public void updateNClob(int columnIndex, Reader reader, long length) throws SQLException { throw notSupported("updates"); }
    @Override public void updateNClob(String columnLabel, Reader reader, long length) throws SQLException { throw notSupported("updates"); }
    @Override public void updateNCharacterStream(int columnIndex, Reader x) throws SQLException { throw notSupported("updates"); }
    @Override public void updateNCharacterStream(String columnLabel, Reader x) throws SQLException { throw notSupported("updates"); }
    @Override public void updateAsciiStream(int columnIndex, InputStream x) throws SQLException { throw notSupported("updates"); }
    @Override public void updateBinaryStream(int columnIndex, InputStream x) throws SQLException { throw notSupported("updates"); }
    @Override public void updateCharacterStream(int columnIndex, Reader x) throws SQLException { throw notSupported("updates"); }
    @Override public void updateAsciiStream(String columnLabel, InputStream x) throws SQLException { throw notSupported("updates"); }
    @Override public void updateBinaryStream(String columnLabel, InputStream x) throws SQLException { throw notSupported("updates"); }
    @Override public void updateCharacterStream(String columnLabel, Reader reader) throws SQLException { throw notSupported("updates"); }
    @Override public void updateBlob(int columnIndex, InputStream inputStream) throws SQLException { throw notSupported("updates"); }
    @Override public void updateBlob(String columnLabel, InputStream inputStream) throws SQLException { throw notSupported("updates"); }
    @Override public void updateClob(int columnIndex, Reader reader) throws SQLException { throw notSupported("updates"); }
    @Override public void updateClob(String columnLabel, Reader reader) throws SQLException { throw notSupported("updates"); }
    @Override public void updateNClob(int columnIndex, Reader reader) throws SQLException { throw notSupported("updates"); }
    @Override public void updateNClob(String columnLabel, Reader reader) throws SQLException { throw notSupported("updates"); }

    @Override @SuppressWarnings("deprecation")
    public BigDecimal getBigDecimal(int columnIndex, int scale) throws SQLException {
        BigDecimal v = getBigDecimal(columnIndex);
        return v == null ? null : v.setScale(scale, java.math.RoundingMode.HALF_UP);
    }
    @Override @SuppressWarnings("deprecation")
    public BigDecimal getBigDecimal(String columnLabel, int scale) throws SQLException {
        BigDecimal v = getBigDecimal(columnLabel);
        return v == null ? null : v.setScale(scale, java.math.RoundingMode.HALF_UP);
    }

    @Override public InputStream getUnicodeStream(int columnIndex) throws SQLException { throw notSupported("UnicodeStream"); }
    @Override public InputStream getUnicodeStream(String columnLabel) throws SQLException { throw notSupported("UnicodeStream"); }

    @Override public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) return iface.cast(this);
        throw new SQLException("Not a wrapper for " + iface);
    }
    @Override public boolean isWrapperFor(Class<?> iface) { return iface.isInstance(this); }
}

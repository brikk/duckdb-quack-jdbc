package com.gizmodata.quack.jdbc.sql;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.sql.Blob;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Arrays;

/**
 * Read-only {@link Blob} backed by a {@code byte[]}.
 */
public final class QuackBlob implements Blob {

    private byte[] bytes;
    private boolean freed;

    public QuackBlob(byte[] bytes) {
        this.bytes = bytes == null ? new byte[0] : bytes;
    }

    @Override
    public long length() throws SQLException {
        checkFreed();
        return bytes.length;
    }

    @Override
    public byte[] getBytes(long pos, int length) throws SQLException {
        checkFreed();
        if (pos < 1 || length < 0) {
            throw new SQLException("Invalid position/length: pos=" + pos + " length=" + length);
        }
        int start = (int) (pos - 1);
        if (start >= bytes.length) return new byte[0];
        int end = Math.min(bytes.length, start + length);
        return Arrays.copyOfRange(bytes, start, end);
    }

    @Override
    public InputStream getBinaryStream() throws SQLException {
        checkFreed();
        return new ByteArrayInputStream(bytes);
    }

    @Override
    public InputStream getBinaryStream(long pos, long length) throws SQLException {
        return new ByteArrayInputStream(getBytes(pos, (int) length));
    }

    @Override
    public long position(byte[] pattern, long start) throws SQLException {
        checkFreed();
        if (pattern == null || pattern.length == 0) return -1L;
        int from = (int) Math.max(1, start) - 1;
        outer:
        for (int i = from; i <= bytes.length - pattern.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (bytes[i + j] != pattern[j]) continue outer;
            }
            return i + 1L;
        }
        return -1L;
    }

    @Override
    public long position(Blob pattern, long start) throws SQLException {
        return position(pattern.getBytes(1, (int) pattern.length()), start);
    }

    @Override
    public int setBytes(long pos, byte[] bytes) throws SQLException {
        throw new SQLFeatureNotSupportedException("Blob.setBytes is not supported by quack-jdbc");
    }

    @Override
    public int setBytes(long pos, byte[] bytes, int offset, int len) throws SQLException {
        throw new SQLFeatureNotSupportedException("Blob.setBytes is not supported by quack-jdbc");
    }

    @Override
    public OutputStream setBinaryStream(long pos) throws SQLException {
        throw new SQLFeatureNotSupportedException("Blob.setBinaryStream is not supported by quack-jdbc");
    }

    @Override
    public void truncate(long len) throws SQLException {
        checkFreed();
        if (len < 0 || len > bytes.length) {
            throw new SQLException("Invalid truncate length: " + len);
        }
        bytes = Arrays.copyOf(bytes, (int) len);
    }

    @Override
    public void free() {
        freed = true;
        bytes = new byte[0];
    }

    private void checkFreed() throws SQLException {
        if (freed) throw new SQLException("Blob.free() has already been called");
    }
}

package com.gizmodata.quack.jdbc.sql;

import java.sql.ParameterMetaData;
import java.sql.SQLException;
import java.sql.Types;

/**
 * Minimal {@link ParameterMetaData} that reports the parameter count
 * inferred from {@code ?} markers in the SQL. The Quack protocol does
 * not (yet) carry server-side bind parameter metadata, so every
 * parameter is reported as {@link Types#OTHER} with
 * {@link ParameterMetaData#parameterModeIn}.
 */
public final class QuackParameterMetaData implements ParameterMetaData {

    private final int parameterCount;

    public QuackParameterMetaData(int parameterCount) {
        this.parameterCount = parameterCount;
    }

    @Override public int getParameterCount() { return parameterCount; }
    @Override public int isNullable(int param) throws SQLException { checkIndex(param); return parameterNullableUnknown; }
    @Override public boolean isSigned(int param) throws SQLException { checkIndex(param); return false; }
    @Override public int getPrecision(int param) throws SQLException { checkIndex(param); return 0; }
    @Override public int getScale(int param) throws SQLException { checkIndex(param); return 0; }
    @Override public int getParameterType(int param) throws SQLException { checkIndex(param); return Types.OTHER; }
    @Override public String getParameterTypeName(int param) throws SQLException { checkIndex(param); return "OTHER"; }
    @Override public String getParameterClassName(int param) throws SQLException { checkIndex(param); return Object.class.getName(); }
    @Override public int getParameterMode(int param) throws SQLException { checkIndex(param); return parameterModeIn; }

    private void checkIndex(int param) throws SQLException {
        if (param < 1 || param > parameterCount) {
            throw new SQLException("Parameter index out of range: " + param + " (count: " + parameterCount + ")");
        }
    }

    @Override public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) return iface.cast(this);
        throw new SQLException("Not a wrapper for " + iface);
    }
    @Override public boolean isWrapperFor(Class<?> iface) { return iface.isInstance(this); }
}

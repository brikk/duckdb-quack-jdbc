package com.gizmodata.quack.jdbc.sql;

import java.sql.SQLException;
import java.sql.Struct;
import java.util.Map;

/**
 * Minimal {@link Struct} wrapper around a STRUCT type name plus its
 * attribute values, used for both decoded STRUCT columns and values
 * built via {@link java.sql.Connection#createStruct}.
 */
public final class QuackStruct implements Struct {

    private final String typeName;
    private final Object[] attributes;

    public QuackStruct(String typeName, Object[] attributes) {
        this.typeName = typeName;
        this.attributes = attributes == null ? new Object[0] : attributes;
    }

    @Override
    public String getSQLTypeName() {
        return typeName;
    }

    @Override
    public Object[] getAttributes() {
        return attributes.clone();
    }

    @Override
    public Object[] getAttributes(Map<String, Class<?>> map) throws SQLException {
        return getAttributes();
    }

    @Override
    public String toString() {
        return typeName + java.util.Arrays.toString(attributes);
    }
}

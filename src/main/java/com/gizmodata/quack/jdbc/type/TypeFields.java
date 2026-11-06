package com.gizmodata.quack.jdbc.type;

import com.gizmodata.quack.jdbc.codec.WireField;

/**
 * Field ids of DuckDB's LogicalType serialization, one enum per wire object.
 * Every ExtraTypeInfo subclass shares {@link ExtraTypeInfo} and then adds its
 * own fields from 200 upward.
 */
public final class TypeFields {

    private TypeFields() {
    }

    public enum LogicalType implements WireField {
        ID(100), TYPE_INFO(101);

        private final int id;
        LogicalType(int id) { this.id = id; }
        @Override public int id() { return id; }
    }

    public enum ExtraTypeInfo implements WireField {
        TYPE(100), ALIAS(101), EXTENSION_INFO(103);

        private final int id;
        ExtraTypeInfo(int id) { this.id = id; }
        @Override public int id() { return id; }
    }

    public enum DecimalInfo implements WireField {
        WIDTH(200), SCALE(201);

        private final int id;
        DecimalInfo(int id) { this.id = id; }
        @Override public int id() { return id; }
    }

    public enum StringInfo implements WireField {
        COLLATION(200);

        private final int id;
        StringInfo(int id) { this.id = id; }
        @Override public int id() { return id; }
    }

    public enum ListInfo implements WireField {
        CHILD_TYPE(200);

        private final int id;
        ListInfo(int id) { this.id = id; }
        @Override public int id() { return id; }
    }

    public enum StructInfo implements WireField {
        CHILD_TYPES(200);

        private final int id;
        StructInfo(int id) { this.id = id; }
        @Override public int id() { return id; }
    }

    public enum EnumInfo implements WireField {
        VALUES_COUNT(200), VALUES(201);

        private final int id;
        EnumInfo(int id) { this.id = id; }
        @Override public int id() { return id; }
    }

    public enum AggregateStateInfo implements WireField {
        FUNCTION_NAME(200), RETURN_TYPE(201), BOUND_ARGUMENT_TYPES(202);

        private final int id;
        AggregateStateInfo(int id) { this.id = id; }
        @Override public int id() { return id; }
    }

    public enum ArrayInfo implements WireField {
        CHILD_TYPE(200), SIZE(201);

        private final int id;
        ArrayInfo(int id) { this.id = id; }
        @Override public int id() { return id; }
    }

    public enum AnyInfo implements WireField {
        TARGET_TYPE(200), CAST_SCORE(201);

        private final int id;
        AnyInfo(int id) { this.id = id; }
        @Override public int id() { return id; }
    }

    public enum IntegerLiteralInfo implements WireField {
        CONSTANT_VALUE(200);

        private final int id;
        IntegerLiteralInfo(int id) { this.id = id; }
        @Override public int id() { return id; }
    }

    public enum TemplateInfo implements WireField {
        NAME(200);

        private final int id;
        TemplateInfo(int id) { this.id = id; }
        @Override public int id() { return id; }
    }

    public enum GeoInfo implements WireField {
        CRS(200);

        private final int id;
        GeoInfo(int id) { this.id = id; }
        @Override public int id() { return id; }
    }

    public enum UnboundInfo implements WireField {
        NAME(200), CATALOG(201), SCHEMA(202), EXPRESSION(204);

        private final int id;
        UnboundInfo(int id) { this.id = id; }
        @Override public int id() { return id; }
    }

    /** One (name, type) pair of {@link StructInfo#CHILD_TYPES}. */
    public enum ChildType implements WireField {
        NAME(0), TYPE(1);

        private final int id;
        ChildType(int id) { this.id = id; }
        @Override public int id() { return id; }
    }

    public enum Crs implements WireField {
        DEFINITION(100);

        private final int id;
        Crs(int id) { this.id = id; }
        @Override public int id() { return id; }
    }
}

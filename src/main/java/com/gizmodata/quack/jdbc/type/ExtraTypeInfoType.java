package com.gizmodata.quack.jdbc.type;

public enum ExtraTypeInfoType {
    INVALID(0),
    GENERIC(1),
    DECIMAL(2),
    STRING(3),
    LIST(4),
    STRUCT(5),
    ENUM(6),
    UNBOUND(7),
    AGGREGATE_STATE(8),
    ARRAY(9),
    ANY(10),
    INTEGER_LITERAL(11),
    TEMPLATE(12),
    GEO(13);

    private final int wireId;

    ExtraTypeInfoType(int wireId) {
        this.wireId = wireId;
    }

    public int wireId() {
        return wireId;
    }

    public static ExtraTypeInfoType fromWireId(int wireId) {
        for (ExtraTypeInfoType t : values()) {
            if (t.wireId == wireId) {
                return t;
            }
        }
        return INVALID;
    }
}

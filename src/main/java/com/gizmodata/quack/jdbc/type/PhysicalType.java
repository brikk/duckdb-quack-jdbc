package com.gizmodata.quack.jdbc.type;

import com.gizmodata.quack.jdbc.QuackProtocolException;

public enum PhysicalType {
    BOOL(1),
    UINT8(2),
    INT8(3),
    UINT16(4),
    INT16(5),
    UINT32(6),
    INT32(7),
    UINT64(8),
    INT64(9),
    FLOAT(11),
    DOUBLE(12),
    INTERVAL(21),
    LIST(23),
    STRUCT(24),
    ARRAY(29),
    VARCHAR(200),
    UINT128(203),
    INT128(204),
    UNKNOWN(205),
    BIT(206),
    INVALID(255);

    private final int wireId;

    PhysicalType(int wireId) {
        this.wireId = wireId;
    }

    public int wireId() {
        return wireId;
    }

    public boolean isConstantSize() {
        return switch (this) {
            case BOOL, UINT8, INT8, UINT16, INT16, UINT32, INT32,
                 UINT64, INT64, FLOAT, DOUBLE, INTERVAL, UINT128, INT128 -> true;
            default -> false;
        };
    }

    public int byteWidth() {
        return switch (this) {
            case BOOL, UINT8, INT8 -> 1;
            case UINT16, INT16 -> 2;
            case UINT32, INT32, FLOAT -> 4;
            case UINT64, INT64, DOUBLE -> 8;
            case INTERVAL, UINT128, INT128 -> 16;
            default -> throw new QuackProtocolException("Physical type " + this + " is not fixed size");
        };
    }
}

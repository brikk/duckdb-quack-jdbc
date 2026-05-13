package com.gizmodata.quack.jdbc.message;

import com.gizmodata.quack.jdbc.QuackProtocolException;

public enum VectorType {
    FLAT(0),
    FSST(1),
    CONSTANT(2),
    DICTIONARY(3),
    SEQUENCE(4);

    private final int wireId;

    VectorType(int wireId) {
        this.wireId = wireId;
    }

    public int wireId() {
        return wireId;
    }

    public static VectorType fromWireId(int wireId) {
        for (VectorType t : values()) {
            if (t.wireId == wireId) {
                return t;
            }
        }
        throw new QuackProtocolException("Unknown vector type " + wireId);
    }
}

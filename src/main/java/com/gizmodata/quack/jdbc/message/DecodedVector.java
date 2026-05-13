package com.gizmodata.quack.jdbc.message;

import com.gizmodata.quack.jdbc.type.LogicalType;

import java.util.List;

public record DecodedVector(LogicalType type, VectorType vectorType, List<Object> values) {
}

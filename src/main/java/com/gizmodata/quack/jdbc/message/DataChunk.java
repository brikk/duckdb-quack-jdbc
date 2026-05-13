package com.gizmodata.quack.jdbc.message;

import com.gizmodata.quack.jdbc.type.LogicalType;

import java.util.List;

public record DataChunk(int rowCount, List<LogicalType> types, List<DecodedVector> columns) {
}

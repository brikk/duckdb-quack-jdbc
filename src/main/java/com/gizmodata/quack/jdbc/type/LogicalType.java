package com.gizmodata.quack.jdbc.type;

import java.util.Optional;

public record LogicalType(LogicalTypeId id, Optional<ExtraTypeInfo> typeInfo) {

    public static LogicalType of(LogicalTypeId id) {
        return new LogicalType(id, Optional.empty());
    }

    public static LogicalType of(LogicalTypeId id, ExtraTypeInfo info) {
        return new LogicalType(id, Optional.of(info));
    }

    public static LogicalType decimal(int width, int scale) {
        return of(LogicalTypeId.DECIMAL, new ExtraTypeInfo.Decimal(width, scale, Optional.empty()));
    }
}

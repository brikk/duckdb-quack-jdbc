package com.gizmodata.quack.jdbc.type;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;

public sealed interface ExtraTypeInfo {

    ExtraTypeInfoType kind();

    Optional<String> alias();

    record Generic(ExtraTypeInfoType kind, Optional<String> alias) implements ExtraTypeInfo {}

    record Decimal(int width, int scale, Optional<String> alias) implements ExtraTypeInfo {
        @Override public ExtraTypeInfoType kind() { return ExtraTypeInfoType.DECIMAL; }
    }

    record StringInfo(String collation, Optional<String> alias) implements ExtraTypeInfo {
        @Override public ExtraTypeInfoType kind() { return ExtraTypeInfoType.STRING; }
    }

    record ListInfo(LogicalType childType, Optional<String> alias) implements ExtraTypeInfo {
        @Override public ExtraTypeInfoType kind() { return ExtraTypeInfoType.LIST; }
    }

    record StructInfo(List<ChildType> childTypes, Optional<String> alias) implements ExtraTypeInfo {
        @Override public ExtraTypeInfoType kind() { return ExtraTypeInfoType.STRUCT; }
    }

    record EnumInfo(List<String> values, Optional<String> alias) implements ExtraTypeInfo {
        @Override public ExtraTypeInfoType kind() { return ExtraTypeInfoType.ENUM; }
    }

    record AggregateStateInfo(String functionName, LogicalType returnType,
                              List<LogicalType> boundArgumentTypes, Optional<String> alias) implements ExtraTypeInfo {
        @Override public ExtraTypeInfoType kind() { return ExtraTypeInfoType.AGGREGATE_STATE; }
    }

    record ArrayInfo(LogicalType childType, int size, Optional<String> alias) implements ExtraTypeInfo {
        @Override public ExtraTypeInfoType kind() { return ExtraTypeInfoType.ARRAY; }
    }

    record AnyInfo(LogicalType targetType, BigInteger castScore, Optional<String> alias) implements ExtraTypeInfo {
        @Override public ExtraTypeInfoType kind() { return ExtraTypeInfoType.ANY; }
    }

    record TemplateInfo(String name, Optional<String> alias) implements ExtraTypeInfo {
        @Override public ExtraTypeInfoType kind() { return ExtraTypeInfoType.TEMPLATE; }
    }

    record IntegerLiteralInfo(Optional<String> alias) implements ExtraTypeInfo {
        @Override public ExtraTypeInfoType kind() { return ExtraTypeInfoType.INTEGER_LITERAL; }
    }

    record GeoInfo(Optional<String> crsDefinition, Optional<String> alias) implements ExtraTypeInfo {
        @Override public ExtraTypeInfoType kind() { return ExtraTypeInfoType.GEO; }
    }

    record UnboundInfo(Optional<String> name, Optional<String> catalog, Optional<String> schema,
                       Optional<String> alias) implements ExtraTypeInfo {
        @Override public ExtraTypeInfoType kind() { return ExtraTypeInfoType.UNBOUND; }
    }
}

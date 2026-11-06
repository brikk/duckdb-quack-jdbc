package com.gizmodata.quack.jdbc.type;

import com.gizmodata.quack.jdbc.QuackUnsupportedTypeException;
import com.gizmodata.quack.jdbc.codec.BinaryReader;
import com.gizmodata.quack.jdbc.codec.BinaryWriter;

import java.util.List;
import java.util.Optional;

public final class LogicalTypeCodec {

    private LogicalTypeCodec() {
    }

    public static void encode(BinaryWriter writer, LogicalType type) {
        writer.writeObject(obj -> {
            obj.writeField(TypeFields.LogicalType.ID, () -> obj.writeUleb(type.id().wireId()));
            type.typeInfo().ifPresent(info ->
                    obj.writeField(TypeFields.LogicalType.TYPE_INFO, () -> obj.writeNullable(info, value -> encodeExtra(obj, value))));
        });
    }

    public static LogicalType decode(BinaryReader reader) {
        return reader.readObject(() -> {
            LogicalTypeId id = LogicalTypeId.fromWireId(
                    reader.readRequiredField(TypeFields.LogicalType.ID, reader::readUlebInt));
            Optional<ExtraTypeInfo> info = Optional.ofNullable(
                    reader.readOptionalField(TypeFields.LogicalType.TYPE_INFO, () -> reader.readNullable(() -> decodeExtra(reader)), null));
            return new LogicalType(id, info);
        });
    }

    private static void encodeExtra(BinaryWriter writer, ExtraTypeInfo info) {
        writer.writeObject(obj -> {
            obj.writeField(TypeFields.ExtraTypeInfo.TYPE, () -> obj.writeUleb(info.kind().wireId()));
            info.alias().ifPresent(a -> obj.writeField(TypeFields.ExtraTypeInfo.ALIAS, () -> obj.writeString(a)));

            if (info instanceof ExtraTypeInfo.Generic) {
                return;
            }
            if (info instanceof ExtraTypeInfo.Decimal d) {
                obj.writeField(TypeFields.DecimalInfo.WIDTH, () -> obj.writeUleb(d.width()));
                obj.writeField(TypeFields.DecimalInfo.SCALE, () -> obj.writeUleb(d.scale()));
                return;
            }
            if (info instanceof ExtraTypeInfo.StringInfo s) {
                obj.writeField(TypeFields.StringInfo.COLLATION, () -> obj.writeString(s.collation() == null ? "" : s.collation()));
                return;
            }
            if (info instanceof ExtraTypeInfo.ListInfo l) {
                obj.writeField(TypeFields.ListInfo.CHILD_TYPE, () -> encode(obj, l.childType()));
                return;
            }
            if (info instanceof ExtraTypeInfo.StructInfo s) {
                obj.writeField(TypeFields.StructInfo.CHILD_TYPES, () -> encodeChildTypes(obj, s.childTypes()));
                return;
            }
            if (info instanceof ExtraTypeInfo.EnumInfo e) {
                obj.writeField(TypeFields.EnumInfo.VALUES_COUNT, () -> obj.writeUleb(e.values().size()));
                obj.writeField(TypeFields.EnumInfo.VALUES, () -> obj.writeList(e.values(), (v, i) -> obj.writeString(v)));
                return;
            }
            if (info instanceof ExtraTypeInfo.AggregateStateInfo a) {
                obj.writeField(TypeFields.AggregateStateInfo.FUNCTION_NAME, () -> obj.writeString(a.functionName()));
                obj.writeField(TypeFields.AggregateStateInfo.RETURN_TYPE, () -> encode(obj, a.returnType()));
                obj.writeField(TypeFields.AggregateStateInfo.BOUND_ARGUMENT_TYPES, () -> obj.writeList(a.boundArgumentTypes(),
                        (t, i) -> encode(obj, t)));
                return;
            }
            if (info instanceof ExtraTypeInfo.ArrayInfo a) {
                obj.writeField(TypeFields.ArrayInfo.CHILD_TYPE, () -> encode(obj, a.childType()));
                obj.writeField(TypeFields.ArrayInfo.SIZE, () -> obj.writeUleb(a.size()));
                return;
            }
            if (info instanceof ExtraTypeInfo.AnyInfo a) {
                obj.writeField(TypeFields.AnyInfo.TARGET_TYPE, () -> encode(obj, a.targetType()));
                obj.writeField(TypeFields.AnyInfo.CAST_SCORE, () -> obj.writeUleb(a.castScore().longValueExact()));
                return;
            }
            if (info instanceof ExtraTypeInfo.TemplateInfo t) {
                obj.writeField(TypeFields.TemplateInfo.NAME, () -> obj.writeString(t.name()));
                return;
            }
            if (info instanceof ExtraTypeInfo.IntegerLiteralInfo) {
                throw new QuackUnsupportedTypeException("Encoding INTEGER_LITERAL metadata is not supported");
            }
            if (info instanceof ExtraTypeInfo.GeoInfo g) {
                obj.writeField(TypeFields.GeoInfo.CRS, () -> encodeCrs(obj, g.crsDefinition()));
                return;
            }
            if (info instanceof ExtraTypeInfo.UnboundInfo u) {
                u.name().ifPresent(n -> obj.writeField(TypeFields.UnboundInfo.NAME, () -> obj.writeString(n)));
                u.catalog().ifPresent(c -> obj.writeField(TypeFields.UnboundInfo.CATALOG, () -> obj.writeString(c)));
                u.schema().ifPresent(s -> obj.writeField(TypeFields.UnboundInfo.SCHEMA, () -> obj.writeString(s)));
            }
        });
    }

    private static ExtraTypeInfo decodeExtra(BinaryReader reader) {
        return reader.readObject(() -> {
            ExtraTypeInfoType type = ExtraTypeInfoType.fromWireId(
                    reader.readRequiredField(TypeFields.ExtraTypeInfo.TYPE, reader::readUlebInt));
            Optional<String> alias = Optional.ofNullable(
                    reader.readOptionalField(TypeFields.ExtraTypeInfo.ALIAS, reader::readString, null));
            reader.readOptionalField(TypeFields.ExtraTypeInfo.EXTENSION_INFO, () -> reader.readNullable(() -> {
                throw new QuackUnsupportedTypeException("Extension type metadata is not supported");
            }), null);

            return switch (type) {
                case INVALID, GENERIC -> new ExtraTypeInfo.Generic(type, alias);
                case DECIMAL -> new ExtraTypeInfo.Decimal(
                        reader.readOptionalField(TypeFields.DecimalInfo.WIDTH, reader::readUlebInt, 0),
                        reader.readOptionalField(TypeFields.DecimalInfo.SCALE, reader::readUlebInt, 0),
                        alias);
                case STRING -> new ExtraTypeInfo.StringInfo(
                        reader.readOptionalField(TypeFields.StringInfo.COLLATION, reader::readString, ""),
                        alias);
                case LIST -> new ExtraTypeInfo.ListInfo(
                        reader.readRequiredField(TypeFields.ListInfo.CHILD_TYPE, () -> decode(reader)),
                        alias);
                case STRUCT -> new ExtraTypeInfo.StructInfo(
                        reader.readOptionalField(TypeFields.StructInfo.CHILD_TYPES, () -> decodeChildTypes(reader), List.of()),
                        alias);
                case ENUM -> {
                    int valuesCount = reader.readRequiredField(TypeFields.EnumInfo.VALUES_COUNT, reader::readUlebInt);
                    List<String> values = reader.readRequiredField(TypeFields.EnumInfo.VALUES,
                            () -> reader.readList(valuesCount, i -> reader.readString()));
                    yield new ExtraTypeInfo.EnumInfo(values, alias);
                }
                case AGGREGATE_STATE -> new ExtraTypeInfo.AggregateStateInfo(
                        reader.readRequiredField(TypeFields.AggregateStateInfo.FUNCTION_NAME, reader::readString),
                        reader.readRequiredField(TypeFields.AggregateStateInfo.RETURN_TYPE, () -> decode(reader)),
                        reader.readRequiredField(TypeFields.AggregateStateInfo.BOUND_ARGUMENT_TYPES, () -> reader.readList(i -> decode(reader))),
                        alias);
                case ARRAY -> new ExtraTypeInfo.ArrayInfo(
                        reader.readRequiredField(TypeFields.ArrayInfo.CHILD_TYPE, () -> decode(reader)),
                        reader.readRequiredField(TypeFields.ArrayInfo.SIZE, reader::readUlebInt),
                        alias);
                case ANY -> new ExtraTypeInfo.AnyInfo(
                        reader.readRequiredField(TypeFields.AnyInfo.TARGET_TYPE, () -> decode(reader)),
                        reader.readRequiredField(TypeFields.AnyInfo.CAST_SCORE, reader::readUlebBigInteger),
                        alias);
                case INTEGER_LITERAL -> {
                    if (reader.nextFieldIs(TypeFields.IntegerLiteralInfo.CONSTANT_VALUE)) {
                        throw new QuackUnsupportedTypeException(
                                "INTEGER_LITERAL type metadata contains a DuckDB Value, which is not supported");
                    }
                    yield new ExtraTypeInfo.IntegerLiteralInfo(alias);
                }
                case TEMPLATE -> new ExtraTypeInfo.TemplateInfo(
                        reader.readOptionalField(TypeFields.TemplateInfo.NAME, reader::readString, ""),
                        alias);
                case GEO -> new ExtraTypeInfo.GeoInfo(
                        Optional.ofNullable(reader.readOptionalField(TypeFields.GeoInfo.CRS, () -> decodeCrs(reader), null)),
                        alias);
                case UNBOUND -> {
                    if (reader.nextFieldIs(TypeFields.UnboundInfo.EXPRESSION)) {
                        throw new QuackUnsupportedTypeException(
                                "UNBOUND type metadata contains a ParsedExpression, which is not supported");
                    }
                    Optional<String> name = Optional.ofNullable(
                            reader.readOptionalField(TypeFields.UnboundInfo.NAME, reader::readString, null));
                    Optional<String> catalog = Optional.ofNullable(
                            reader.readOptionalField(TypeFields.UnboundInfo.CATALOG, reader::readString, null));
                    Optional<String> schema = Optional.ofNullable(
                            reader.readOptionalField(TypeFields.UnboundInfo.SCHEMA, reader::readString, null));
                    yield new ExtraTypeInfo.UnboundInfo(name, catalog, schema, alias);
                }
            };
        });
    }

    private static void encodeChildTypes(BinaryWriter writer, List<ChildType> children) {
        writer.writeList(children, (child, i) -> writer.writeObject(pair -> {
            pair.writeField(TypeFields.ChildType.NAME, () -> pair.writeString(child.name()));
            pair.writeField(TypeFields.ChildType.TYPE, () -> encode(pair, child.type()));
        }));
    }

    private static List<ChildType> decodeChildTypes(BinaryReader reader) {
        return reader.readList(i -> reader.readObject(() -> new ChildType(
                reader.readRequiredField(TypeFields.ChildType.NAME, reader::readString),
                reader.readRequiredField(TypeFields.ChildType.TYPE, () -> decode(reader)))));
    }

    private static void encodeCrs(BinaryWriter writer, Optional<String> definition) {
        writer.writeObject(obj -> definition.ifPresent(d ->
                obj.writeField(TypeFields.Crs.DEFINITION, () -> obj.writeString(d))));
    }

    private static String decodeCrs(BinaryReader reader) {
        return reader.readObject(() -> reader.readOptionalField(TypeFields.Crs.DEFINITION, reader::readString, null));
    }
}

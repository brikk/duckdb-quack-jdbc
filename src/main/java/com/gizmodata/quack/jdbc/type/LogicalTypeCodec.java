package com.gizmodata.quack.jdbc.type;

import com.gizmodata.quack.jdbc.QuackProtocolException;
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
            obj.writeField(100, () -> obj.writeUleb(type.id().wireId()));
            type.typeInfo().ifPresent(info ->
                    obj.writeField(101, () -> obj.writeNullable(info, value -> encodeExtra(obj, value))));
        });
    }

    public static LogicalType decode(BinaryReader reader) {
        return reader.readObject(() -> {
            LogicalTypeId id = LogicalTypeId.fromWireId(
                    reader.readRequiredField(100, reader::readUlebInt));
            Optional<ExtraTypeInfo> info = Optional.ofNullable(
                    reader.readOptionalField(101, () -> reader.readNullable(() -> decodeExtra(reader)), null));
            return new LogicalType(id, info);
        });
    }

    private static void encodeExtra(BinaryWriter writer, ExtraTypeInfo info) {
        writer.writeObject(obj -> {
            obj.writeField(100, () -> obj.writeUleb(info.kind().wireId()));
            info.alias().ifPresent(a -> obj.writeField(101, () -> obj.writeString(a)));

            if (info instanceof ExtraTypeInfo.Generic) {
                return;
            }
            if (info instanceof ExtraTypeInfo.Decimal d) {
                obj.writeField(200, () -> obj.writeUleb(d.width()));
                obj.writeField(201, () -> obj.writeUleb(d.scale()));
                return;
            }
            if (info instanceof ExtraTypeInfo.StringInfo s) {
                obj.writeField(200, () -> obj.writeString(s.collation() == null ? "" : s.collation()));
                return;
            }
            if (info instanceof ExtraTypeInfo.ListInfo l) {
                obj.writeField(200, () -> encode(obj, l.childType()));
                return;
            }
            if (info instanceof ExtraTypeInfo.StructInfo s) {
                obj.writeField(200, () -> encodeChildTypes(obj, s.childTypes()));
                return;
            }
            if (info instanceof ExtraTypeInfo.EnumInfo e) {
                obj.writeField(200, () -> obj.writeUleb(e.values().size()));
                obj.writeField(201, () -> obj.writeList(e.values(), (v, i) -> obj.writeString(v)));
                return;
            }
            if (info instanceof ExtraTypeInfo.AggregateStateInfo a) {
                obj.writeField(200, () -> obj.writeString(a.functionName()));
                obj.writeField(201, () -> encode(obj, a.returnType()));
                obj.writeField(202, () -> obj.writeList(a.boundArgumentTypes(),
                        (t, i) -> encode(obj, t)));
                return;
            }
            if (info instanceof ExtraTypeInfo.ArrayInfo a) {
                obj.writeField(200, () -> encode(obj, a.childType()));
                obj.writeField(201, () -> obj.writeUleb(a.size()));
                return;
            }
            if (info instanceof ExtraTypeInfo.AnyInfo a) {
                obj.writeField(200, () -> encode(obj, a.targetType()));
                obj.writeField(201, () -> obj.writeUleb(a.castScore().longValueExact()));
                return;
            }
            if (info instanceof ExtraTypeInfo.TemplateInfo t) {
                obj.writeField(200, () -> obj.writeString(t.name()));
                return;
            }
            if (info instanceof ExtraTypeInfo.IntegerLiteralInfo) {
                throw new QuackUnsupportedTypeException("Encoding INTEGER_LITERAL metadata is not supported");
            }
            if (info instanceof ExtraTypeInfo.GeoInfo g) {
                obj.writeField(200, () -> encodeCrs(obj, g.crsDefinition()));
                return;
            }
            if (info instanceof ExtraTypeInfo.UnboundInfo u) {
                u.name().ifPresent(n -> obj.writeField(200, () -> obj.writeString(n)));
                u.catalog().ifPresent(c -> obj.writeField(201, () -> obj.writeString(c)));
                u.schema().ifPresent(s -> obj.writeField(202, () -> obj.writeString(s)));
            }
        });
    }

    private static ExtraTypeInfo decodeExtra(BinaryReader reader) {
        return reader.readObject(() -> {
            ExtraTypeInfoType type = ExtraTypeInfoType.fromWireId(
                    reader.readRequiredField(100, reader::readUlebInt));
            Optional<String> alias = Optional.ofNullable(
                    reader.readOptionalField(101, reader::readString, null));
            reader.readOptionalField(103, () -> reader.readNullable(() -> {
                throw new QuackUnsupportedTypeException("Extension type metadata is not supported");
            }), null);

            return switch (type) {
                case INVALID, GENERIC -> new ExtraTypeInfo.Generic(type, alias);
                case DECIMAL -> new ExtraTypeInfo.Decimal(
                        reader.readOptionalField(200, reader::readUlebInt, 0),
                        reader.readOptionalField(201, reader::readUlebInt, 0),
                        alias);
                case STRING -> new ExtraTypeInfo.StringInfo(
                        reader.readOptionalField(200, reader::readString, ""),
                        alias);
                case LIST -> new ExtraTypeInfo.ListInfo(
                        reader.readRequiredField(200, () -> decode(reader)),
                        alias);
                case STRUCT -> new ExtraTypeInfo.StructInfo(
                        reader.readOptionalField(200, () -> decodeChildTypes(reader), List.of()),
                        alias);
                case ENUM -> {
                    int valuesCount = reader.readRequiredField(200, reader::readUlebInt);
                    List<String> values = reader.readRequiredField(201,
                            () -> reader.readList(i -> reader.readString()));
                    if (values.size() != valuesCount) {
                        throw new QuackProtocolException(
                                "ENUM metadata declared " + valuesCount + " values but serialized " + values.size());
                    }
                    yield new ExtraTypeInfo.EnumInfo(values, alias);
                }
                case AGGREGATE_STATE -> new ExtraTypeInfo.AggregateStateInfo(
                        reader.readRequiredField(200, reader::readString),
                        reader.readRequiredField(201, () -> decode(reader)),
                        reader.readRequiredField(202, () -> reader.readList(i -> decode(reader))),
                        alias);
                case ARRAY -> new ExtraTypeInfo.ArrayInfo(
                        reader.readRequiredField(200, () -> decode(reader)),
                        reader.readRequiredField(201, reader::readUlebInt),
                        alias);
                case ANY -> new ExtraTypeInfo.AnyInfo(
                        reader.readRequiredField(200, () -> decode(reader)),
                        reader.readRequiredField(201, reader::readUlebBigInteger),
                        alias);
                case INTEGER_LITERAL -> {
                    if (!reader.eof() && reader.peekFieldId() == 200) {
                        throw new QuackUnsupportedTypeException(
                                "INTEGER_LITERAL type metadata contains a DuckDB Value, which is not supported");
                    }
                    yield new ExtraTypeInfo.IntegerLiteralInfo(alias);
                }
                case TEMPLATE -> new ExtraTypeInfo.TemplateInfo(
                        reader.readOptionalField(200, reader::readString, ""),
                        alias);
                case GEO -> new ExtraTypeInfo.GeoInfo(
                        Optional.ofNullable(reader.readOptionalField(200, () -> decodeCrs(reader), null)),
                        alias);
                case UNBOUND -> {
                    if (!reader.eof() && reader.peekFieldId() == 204) {
                        throw new QuackUnsupportedTypeException(
                                "UNBOUND type metadata contains a ParsedExpression, which is not supported");
                    }
                    Optional<String> name = Optional.ofNullable(
                            reader.readOptionalField(200, reader::readString, null));
                    Optional<String> catalog = Optional.ofNullable(
                            reader.readOptionalField(201, reader::readString, null));
                    Optional<String> schema = Optional.ofNullable(
                            reader.readOptionalField(202, reader::readString, null));
                    yield new ExtraTypeInfo.UnboundInfo(name, catalog, schema, alias);
                }
            };
        });
    }

    private static void encodeChildTypes(BinaryWriter writer, List<ChildType> children) {
        writer.writeList(children, (child, i) -> writer.writeObject(pair -> {
            pair.writeField(0, () -> pair.writeString(child.name()));
            pair.writeField(1, () -> encode(pair, child.type()));
        }));
    }

    private static List<ChildType> decodeChildTypes(BinaryReader reader) {
        return reader.readList(i -> reader.readObject(() -> new ChildType(
                reader.readRequiredField(0, reader::readString),
                reader.readRequiredField(1, () -> decode(reader)))));
    }

    private static void encodeCrs(BinaryWriter writer, Optional<String> definition) {
        writer.writeObject(obj -> definition.ifPresent(d ->
                obj.writeField(100, () -> obj.writeString(d))));
    }

    private static String decodeCrs(BinaryReader reader) {
        return reader.readObject(() -> reader.readOptionalField(100, reader::readString, null));
    }
}

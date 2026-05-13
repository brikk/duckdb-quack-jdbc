package com.gizmodata.quack.jdbc.message;

import com.gizmodata.quack.jdbc.QuackProtocolException;
import com.gizmodata.quack.jdbc.QuackUnsupportedTypeException;
import com.gizmodata.quack.jdbc.codec.BinaryReader;
import com.gizmodata.quack.jdbc.codec.BinaryWriter;
import com.gizmodata.quack.jdbc.codec.HugeIntParts;
import com.gizmodata.quack.jdbc.type.ChildType;
import com.gizmodata.quack.jdbc.type.ExtraTypeInfo;
import com.gizmodata.quack.jdbc.type.LogicalType;
import com.gizmodata.quack.jdbc.type.LogicalTypeCodec;
import com.gizmodata.quack.jdbc.type.LogicalTypeId;
import com.gizmodata.quack.jdbc.type.PhysicalType;
import com.gizmodata.quack.jdbc.type.PhysicalTypeUtil;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Decode and (eventually) encode DuckDB DataChunks from the Quack wire
 * format. Decoded values use natural Java types:
 *
 * <ul>
 *   <li>BOOLEAN -&gt; {@link Boolean}</li>
 *   <li>TINYINT/SMALLINT/INTEGER -&gt; {@link Integer} / {@link Short} /
 *       {@link Byte}</li>
 *   <li>BIGINT -&gt; {@link Long}; UBIGINT also {@link Long} (bits raw)</li>
 *   <li>HUGEINT/UHUGEINT -&gt; {@link BigInteger}</li>
 *   <li>FLOAT/DOUBLE -&gt; {@link Float} / {@link Double}</li>
 *   <li>DECIMAL -&gt; {@link BigDecimal}</li>
 *   <li>DATE -&gt; {@link LocalDate}</li>
 *   <li>TIME / TIME_NS -&gt; {@link LocalTime}</li>
 *   <li>TIMESTAMP variants -&gt; {@link LocalDateTime}; TIMESTAMP_TZ -&gt;
 *       {@link OffsetDateTime}</li>
 *   <li>INTERVAL -&gt; {@link IntervalValue}</li>
 *   <li>VARCHAR/CHAR -&gt; {@link String}; BLOB/BIT/GEOMETRY -&gt; {@code byte[]}</li>
 *   <li>UUID -&gt; {@link UUID}</li>
 *   <li>ENUM -&gt; {@link String} (the enum value)</li>
 *   <li>STRUCT -&gt; {@code Map<String,Object>}; LIST/MAP -&gt; {@code List<Object>};
 *       ARRAY -&gt; {@code List<Object>}</li>
 *   <li>NULL -&gt; {@code null}</li>
 * </ul>
 */
public final class VectorCodec {

    private VectorCodec() {
    }

    public static DataChunk decodeDataChunkWrapper(BinaryReader reader) {
        return reader.readObject(() ->
                reader.readRequiredField(300, () -> decodeDataChunk(reader)));
    }

    public static DataChunk decodeDataChunk(BinaryReader reader) {
        return reader.readObject(() -> {
            int rowCount = reader.readRequiredField(100, reader::readUlebInt);
            List<LogicalType> types = reader.readRequiredField(101,
                    () -> reader.readList(i -> LogicalTypeCodec.decode(reader)));
            List<DecodedVector> columns = reader.readRequiredField(102,
                    () -> reader.readList(i -> {
                        if (i >= types.size()) {
                            throw new QuackProtocolException(
                                    "Column vector " + i + " has no matching logical type");
                        }
                        return decodeVector(reader, types.get(i), rowCount);
                    }));
            if (columns.size() != types.size()) {
                throw new QuackProtocolException(
                        "DataChunk declared " + types.size() + " types but serialized "
                                + columns.size() + " columns");
            }
            return new DataChunk(rowCount, types, columns);
        });
    }

    public static DecodedVector decodeVector(BinaryReader reader, LogicalType type, int count) {
        return reader.readObject(() -> decodeVectorBody(reader, type, count));
    }

    private static DecodedVector decodeVectorBody(BinaryReader reader, LogicalType type, int count) {
        int vectorTypeId = reader.readOptionalField(90, reader::readUlebInt, VectorType.FLAT.wireId());
        VectorType vectorType = VectorType.fromWireId(vectorTypeId);
        return switch (vectorType) {
            case FLAT -> decodeFlatVectorBody(reader, type, count, vectorType);
            case FSST -> throw new QuackUnsupportedTypeException(
                    "FSST-compressed vectors are not yet supported");
            case CONSTANT -> {
                DecodedVector decoded = decodeVectorBody(reader, type, count > 0 ? 1 : 0);
                Object value = decoded.values().isEmpty() ? null : decoded.values().get(0);
                List<Object> values = new ArrayList<>(count);
                for (int i = 0; i < count; i++) values.add(value);
                yield new DecodedVector(type, vectorType, values);
            }
            case DICTIONARY -> {
                int[] selection = reader.readRequiredField(91, () -> readSelectionVector(reader, count));
                int dictionaryCount = reader.readRequiredField(92, reader::readUlebInt);
                DecodedVector dictionary = decodeVectorBody(reader, type, dictionaryCount);
                List<Object> values = new ArrayList<>(count);
                for (int idx : selection) {
                    if (idx < 0 || idx >= dictionary.values().size()) {
                        throw new QuackProtocolException("Dictionary selection " + idx + " is out of range");
                    }
                    values.add(dictionary.values().get(idx));
                }
                yield new DecodedVector(type, vectorType, values);
            }
            case SEQUENCE -> {
                long start = reader.readRequiredField(91, reader::readSlebLong);
                long increment = reader.readRequiredField(92, reader::readSlebLong);
                List<Object> values = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    long v = start + increment * (long) i;
                    values.add(decodeSequenceValue(type, v));
                }
                yield new DecodedVector(type, vectorType, values);
            }
        };
    }

    private static DecodedVector decodeFlatVectorBody(BinaryReader reader, LogicalType type, int count,
                                                      VectorType vectorType) {
        if (type.id() == LogicalTypeId.GEOMETRY && !reader.eof() && reader.peekFieldId() == 99) {
            reader.readRequiredField(99, reader::readUlebInt);
        }

        boolean hasValidityMask = reader.readRequiredField(100, reader::readBool);
        boolean[] validity = hasValidityMask
                ? reader.readRequiredField(101, () -> readValidityMask(reader, count))
                : null;
        PhysicalType physicalType = PhysicalTypeUtil.getPhysicalType(type);

        if (physicalType.isConstantSize()) {
            int byteLength = physicalType.byteWidth() * count;
            byte[] bytes = reader.readRequiredField(102, reader::readBlob);
            if (bytes.length != byteLength) {
                throw new QuackProtocolException(
                        "Fixed-size vector data has " + bytes.length + " bytes, expected " + byteLength);
            }
            List<Object> values = decodeFixedValues(type, physicalType, bytes, count, validity);
            return new DecodedVector(type, vectorType, values);
        }

        return switch (physicalType) {
            case VARCHAR -> {
                List<byte[]> raw = reader.readRequiredField(102,
                        () -> reader.readList(i -> reader.readStringBytes()));
                List<Object> values = new ArrayList<>(raw.size());
                for (int i = 0; i < raw.size(); i++) {
                    values.add(isValid(validity, i) ? decodeStringLikeValue(type, raw.get(i)) : null);
                }
                yield new DecodedVector(type, vectorType, values);
            }
            case STRUCT -> {
                List<ChildType> children = PhysicalTypeUtil.getStructChildren(type);
                List<DecodedVector> childVectors = reader.readRequiredField(103,
                        () -> reader.readList(i -> {
                            if (i >= children.size()) {
                                throw new QuackProtocolException(
                                        "STRUCT child vector " + i + " has no matching type metadata");
                            }
                            return decodeVector(reader, children.get(i).type(), count);
                        }));
                List<Object> values = new ArrayList<>(count);
                for (int row = 0; row < count; row++) {
                    if (!isValid(validity, row)) {
                        values.add(null);
                        continue;
                    }
                    Map<String, Object> rowMap = new LinkedHashMap<>();
                    for (int c = 0; c < children.size(); c++) {
                        rowMap.put(children.get(c).name(), childVectors.get(c).values().get(row));
                    }
                    values.add(rowMap);
                }
                yield new DecodedVector(type, vectorType, values);
            }
            case LIST -> {
                int listSize = reader.readRequiredField(104, reader::readUlebInt);
                List<ListEntry> entries = reader.readRequiredField(105,
                        () -> readListEntries(reader, count));
                LogicalType childType = PhysicalTypeUtil.getChildType(type);
                DecodedVector childVector = reader.readRequiredField(106,
                        () -> decodeVector(reader, childType, listSize));
                List<Object> values = new ArrayList<>(count);
                for (int row = 0; row < count; row++) {
                    if (!isValid(validity, row)) {
                        values.add(null);
                        continue;
                    }
                    ListEntry e = entries.get(row);
                    values.add(new ArrayList<>(childVector.values().subList(e.offset, e.offset + e.length)));
                }
                yield new DecodedVector(type, vectorType, values);
            }
            case ARRAY -> {
                int arraySize = reader.readRequiredField(103, reader::readUlebInt);
                int expected = PhysicalTypeUtil.getArraySize(type);
                if (arraySize != expected) {
                    throw new QuackProtocolException("ARRAY vector serialized size " + arraySize
                            + ", expected " + expected);
                }
                LogicalType childType = PhysicalTypeUtil.getChildType(type);
                DecodedVector childVector = reader.readRequiredField(104,
                        () -> decodeVector(reader, childType, arraySize * count));
                List<Object> values = new ArrayList<>(count);
                for (int row = 0; row < count; row++) {
                    if (!isValid(validity, row)) {
                        values.add(null);
                        continue;
                    }
                    int offset = row * arraySize;
                    values.add(new ArrayList<>(childVector.values().subList(offset, offset + arraySize)));
                }
                yield new DecodedVector(type, vectorType, values);
            }
            default -> throw new QuackUnsupportedTypeException(
                    "Variable-width physical type " + physicalType + " is not supported");
        };
    }

    private static List<Object> decodeFixedValues(LogicalType type, PhysicalType physicalType,
                                                  byte[] bytes, int count, boolean[] validity) {
        BinaryReader reader = new BinaryReader(bytes);
        List<Object> values = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Object v = decodeFixedValue(reader, type, physicalType);
            values.add(isValid(validity, i) ? v : null);
        }
        reader.assertEof();
        return values;
    }

    private static Object decodeFixedValue(BinaryReader reader, LogicalType type, PhysicalType physicalType) {
        return switch (physicalType) {
            case BOOL -> reader.readFixedUint8() != 0;
            case INT8 -> (int) reader.readFixedInt8();
            case UINT8 -> decodeEnumOrInt(type, reader.readFixedUint8());
            case INT16 -> {
                int value = reader.readFixedInt16();
                yield type.id() == LogicalTypeId.DECIMAL
                        ? decimalFromUnscaled(type, BigInteger.valueOf(value))
                        : (Object) value;
            }
            case UINT16 -> decodeEnumOrInt(type, reader.readFixedUint16());
            case INT32 -> {
                int value = reader.readFixedInt32();
                if (type.id() == LogicalTypeId.DATE) {
                    yield LocalDate.ofEpochDay(value);
                }
                yield type.id() == LogicalTypeId.DECIMAL
                        ? decimalFromUnscaled(type, BigInteger.valueOf(value))
                        : (Object) value;
            }
            case UINT32 -> decodeEnumOrLong(type, reader.readFixedUint32());
            case INT64 -> {
                long value = reader.readFixedInt64();
                yield decodeInt64LogicalValue(type, value);
            }
            case UINT64 -> reader.readFixedUint64(); // raw long bits, treat as unsigned
            case FLOAT -> reader.readFixedFloat32();
            case DOUBLE -> reader.readFixedFloat64();
            case INT128 -> {
                long lower = reader.readFixedUint64();
                long upper = reader.readFixedInt64();
                if (type.id() == LogicalTypeId.UUID) {
                    yield uuidFromHugeIntParts(upper, lower);
                }
                BigInteger value = new HugeIntParts(upper, lower).toSignedBigInteger();
                yield type.id() == LogicalTypeId.DECIMAL ? decimalFromUnscaled(type, value) : (Object) value;
            }
            case UINT128 -> {
                long lower = reader.readFixedUint64();
                long upper = reader.readFixedUint64();
                yield new HugeIntParts(upper, lower).toUnsignedBigInteger();
            }
            case INTERVAL -> new IntervalValue(
                    reader.readFixedInt32(),
                    reader.readFixedInt32(),
                    reader.readFixedInt64());
            default -> throw new QuackUnsupportedTypeException(
                    "Cannot decode fixed physical type " + physicalType);
        };
    }

    private static Object decodeSequenceValue(LogicalType type, long value) {
        return switch (type.id()) {
            case INTEGER -> (int) value;
            case DATE -> LocalDate.ofEpochDay(value);
            case BIGINT -> value;
            default -> decodeInt64LogicalValue(type, value);
        };
    }

    private static Object decodeInt64LogicalValue(LogicalType type, long value) {
        return switch (type.id()) {
            case TIME -> microsToLocalTime(value);
            case TIME_NS -> LocalTime.ofNanoOfDay(value);
            case TIME_TZ -> value; // raw bits; full TIME_TZ decode is TODO
            case TIMESTAMP_SEC -> LocalDateTime.ofInstant(Instant.ofEpochSecond(value), ZoneOffset.UTC);
            case TIMESTAMP_MS -> LocalDateTime.ofInstant(Instant.ofEpochMilli(value), ZoneOffset.UTC);
            case TIMESTAMP -> microsToLocalDateTime(value);
            case TIMESTAMP_NS -> LocalDateTime.ofInstant(
                    Instant.ofEpochSecond(value / 1_000_000_000L, value % 1_000_000_000L), ZoneOffset.UTC);
            case TIMESTAMP_TZ -> OffsetDateTime.ofInstant(microsToInstant(value), ZoneOffset.UTC);
            case DECIMAL -> decimalFromUnscaled(type, BigInteger.valueOf(value));
            default -> value;
        };
    }

    private static LocalTime microsToLocalTime(long micros) {
        long nanos = Math.multiplyExact(micros, 1_000L);
        return LocalTime.ofNanoOfDay(Math.floorMod(nanos, 86_400L * 1_000_000_000L));
    }

    private static LocalDateTime microsToLocalDateTime(long micros) {
        return LocalDateTime.ofInstant(microsToInstant(micros), ZoneOffset.UTC);
    }

    private static Instant microsToInstant(long micros) {
        long seconds = Math.floorDiv(micros, 1_000_000L);
        long microsPart = Math.floorMod(micros, 1_000_000L);
        return Instant.ofEpochSecond(seconds, microsPart * 1_000L);
    }

    private static Object decodeEnumOrInt(LogicalType type, int index) {
        if (type.id() != LogicalTypeId.ENUM) {
            return index;
        }
        List<String> values = PhysicalTypeUtil.getEnumValues(type);
        if (index < 0 || index >= values.size()) {
            throw new QuackProtocolException("ENUM index " + index + " is out of range");
        }
        return values.get(index);
    }

    private static Object decodeEnumOrLong(LogicalType type, long index) {
        if (type.id() != LogicalTypeId.ENUM) {
            return index;
        }
        if (index < 0 || index >= Integer.MAX_VALUE) {
            throw new QuackProtocolException("ENUM index " + index + " is out of range");
        }
        return decodeEnumOrInt(type, (int) index);
    }

    private static Object decodeStringLikeValue(LogicalType type, byte[] raw) {
        return switch (type.id()) {
            case BLOB, GEOMETRY, BIT -> raw;
            default -> new String(raw, java.nio.charset.StandardCharsets.UTF_8);
        };
    }

    private static BigDecimal decimalFromUnscaled(LogicalType type, BigInteger value) {
        ExtraTypeInfo info = type.typeInfo().orElseThrow(
                () -> new QuackProtocolException("DECIMAL value is missing DecimalTypeInfo"));
        if (!(info instanceof ExtraTypeInfo.Decimal d)) {
            throw new QuackProtocolException("DECIMAL value is missing DecimalTypeInfo");
        }
        return new BigDecimal(value, d.scale());
    }

    private static UUID uuidFromHugeIntParts(long upper, long lower) {
        long displayUpper = upper ^ (1L << 63);
        return new UUID(displayUpper, lower);
    }

    private static int[] readSelectionVector(BinaryReader reader, int count) {
        int expectedBytes = count * 4;
        byte[] bytes = reader.readBlob();
        if (bytes.length != expectedBytes) {
            throw new QuackProtocolException("Selection vector has " + bytes.length
                    + " bytes, expected " + expectedBytes);
        }
        int[] out = new int[count];
        for (int i = 0; i < count; i++) {
            int o = i * 4;
            out[i] = (bytes[o] & 0xFF)
                    | ((bytes[o + 1] & 0xFF) << 8)
                    | ((bytes[o + 2] & 0xFF) << 16)
                    | ((bytes[o + 3] & 0xFF) << 24);
        }
        return out;
    }

    private static boolean[] readValidityMask(BinaryReader reader, int count) {
        int expected = validityMaskSize(count);
        byte[] bytes = reader.readBlob();
        if (bytes.length != expected) {
            throw new QuackProtocolException("Validity mask has " + bytes.length
                    + " bytes, expected " + expected);
        }
        boolean[] out = new boolean[count];
        for (int i = 0; i < count; i++) {
            int b = bytes[i / 8] & 0xFF;
            out[i] = (b & (1 << (i % 8))) != 0;
        }
        return out;
    }

    private static int validityMaskSize(int count) {
        return ((count + 63) / 64) * 8;
    }

    private static boolean isValid(boolean[] validity, int index) {
        return validity == null || validity[index];
    }

    private static List<ListEntry> readListEntries(BinaryReader reader, int count) {
        List<ListEntry> entries = reader.readList(i -> reader.readObject(() -> new ListEntry(
                reader.readRequiredField(100, reader::readUlebInt),
                reader.readRequiredField(101, reader::readUlebInt))));
        if (entries.size() != count) {
            throw new QuackProtocolException("LIST vector serialized " + entries.size()
                    + " entries for " + count + " rows");
        }
        return entries;
    }

    /** Serialize a chunk wrapper. For now only used by tests / future APPEND. */
    public static void encodeDataChunkWrapper(BinaryWriter writer, DataChunk chunk) {
        writer.writeObject(obj -> obj.writeField(300, () -> encodeDataChunk(obj, chunk)));
    }

    public static void encodeDataChunk(BinaryWriter writer, DataChunk chunk) {
        if (chunk.types().size() != chunk.columns().size()) {
            throw new QuackProtocolException("DataChunk type count must match column count");
        }
        writer.writeObject(obj -> {
            obj.writeField(100, () -> obj.writeUleb(chunk.rowCount()));
            obj.writeField(101, () -> obj.writeList(chunk.types(),
                    (t, i) -> LogicalTypeCodec.encode(obj, t)));
            // Vector encoding is decoder-complete; encoder is intentionally stubbed
            // for now since this driver only consumes results. APPEND support will
            // fill this in.
            throw new QuackUnsupportedTypeException(
                    "Vector encoding is not yet implemented (decoder is); APPEND support is on the roadmap");
        });
    }

    private record ListEntry(int offset, int length) {
    }
}

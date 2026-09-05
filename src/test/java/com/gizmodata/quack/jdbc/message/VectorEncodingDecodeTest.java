package com.gizmodata.quack.jdbc.message;

import com.gizmodata.quack.jdbc.QuackProtocolException;
import com.gizmodata.quack.jdbc.QuackUnsupportedTypeException;
import com.gizmodata.quack.jdbc.codec.BinaryReader;
import com.gizmodata.quack.jdbc.codec.BinaryWriter;
import com.gizmodata.quack.jdbc.type.ChildType;
import com.gizmodata.quack.jdbc.type.ExtraTypeInfo;
import com.gizmodata.quack.jdbc.type.LogicalType;
import com.gizmodata.quack.jdbc.type.LogicalTypeId;
import com.gizmodata.quack.jdbc.type.PhysicalTypeUtil;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Decode tests for the compressed vector encodings the Quack server can emit
 * (CONSTANT, DICTIONARY, SEQUENCE) plus the FSST guard. The FLAT encoder never
 * produces these, so the wire bytes are hand-crafted to exercise the decode
 * branches directly.
 */
class VectorEncodingDecodeTest {

    private static final LogicalType INT = LogicalType.of(LogicalTypeId.INTEGER);
    private static final LogicalType BIGINT = LogicalType.of(LogicalTypeId.BIGINT);
    private static final LogicalType UBIGINT = LogicalType.of(LogicalTypeId.UBIGINT);

    private static byte[] le32(int... values) {
        ByteBuffer buf = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int v : values) buf.putInt(v);
        return buf.array();
    }

    private static byte[] le64(long... values) {
        ByteBuffer buf = ByteBuffer.allocate(values.length * 8).order(ByteOrder.LITTLE_ENDIAN);
        for (long value : values) buf.putLong(value);
        return buf.array();
    }

    private static DecodedVector decode(LogicalType type, int count, byte[] object) {
        return VectorCodec.decodeVector(new BinaryReader(object), type, count);
    }

    @Test
    void unnamedStructPreservesPositionsInFlatConstantAndDictionaryVectors() {
        LogicalType tuple = LogicalType.of(LogicalTypeId.STRUCT, new ExtraTypeInfo.StructInfo(
                List.of(new ChildType("", INT), new ChildType("", INT)), Optional.empty()));
        for (VectorType encoding : new VectorType[]{VectorType.FLAT, VectorType.CONSTANT, VectorType.DICTIONARY}) {
            int rows = encoding == VectorType.CONSTANT ? 1 : 3;
            BinaryWriter w = new BinaryWriter();
            w.writeObject(obj -> {
                if (encoding != VectorType.FLAT) {
                    obj.writeField(90, () -> obj.writeUleb(encoding.wireId()));
                }
                if (encoding == VectorType.DICTIONARY) {
                    obj.writeField(91, () -> obj.writeBlob(le32(2, 0, 1, 2)));
                    obj.writeField(92, () -> obj.writeUleb(rows));
                }
                obj.writeField(100, () -> obj.writeBool(rows > 1));
                if (rows > 1) obj.writeField(101, () -> obj.writeBlob(le64(5)));
                obj.writeField(103, () -> {
                    obj.writeUleb(2);
                    obj.writeObject(child -> {
                        child.writeField(100, () -> child.writeBool(rows > 1));
                        if (rows > 1) child.writeField(101, () -> child.writeBlob(le64(1)));
                        child.writeField(102, () -> child.writeBlob(rows == 1 ? le32(11) : le32(11, 0, 0)));
                    });
                    obj.writeObject(child -> {
                        child.writeField(100, () -> child.writeBool(false));
                        child.writeField(102, () -> child.writeBlob(rows == 1 ? le32(22) : le32(22, 0, 33)));
                    });
                });
            });
            int[] selection = encoding == VectorType.CONSTANT ? new int[]{0, 0, 0, 0}
                    : encoding == VectorType.DICTIONARY ? new int[]{2, 0, 1, 2} : new int[]{0, 1, 2};
            Object[] expected = {List.of(11, 22), null, Arrays.asList(null, 33)};
            BinaryReader reader = new BinaryReader(w.toByteArray());
            DecodedVector v = VectorCodec.decodeVector(reader, tuple, selection.length);
            reader.assertEof();
            for (int i = 0; i < selection.length; i++) {
                assertEquals(expected[selection[i]], v.getObject(i), encoding + " row " + i);
            }
        }
    }

    @Test
    void structDecodeRejectsMissingOrExtraChildren() {
        for (String name : new String[]{"", "a"}) {
            LogicalType struct = LogicalType.of(LogicalTypeId.STRUCT, new ExtraTypeInfo.StructInfo(
                    List.of(new ChildType(name, INT), new ChildType(name.isEmpty() ? "" : "b", INT)), Optional.empty()));
            for (int childCount : new int[]{0, 1, 3}) {
                BinaryWriter w = new BinaryWriter();
                w.writeObject(obj -> {
                    obj.writeField(100, () -> obj.writeBool(false));
                    obj.writeField(103, () -> {
                        obj.writeUleb(childCount);
                        for (int i = 0; i < childCount; i++) obj.writeObject(child -> {
                            child.writeField(100, () -> child.writeBool(false));
                            child.writeField(102, () -> child.writeBlob(le32(11)));
                        });
                    });
                });
                assertThrows(QuackProtocolException.class, () -> decode(struct, 1, w.toByteArray()));
            }
        }
    }

    @Test
    void constantVectorBroadcastsSingleValue() {
        BinaryWriter w = new BinaryWriter();
        w.writeObject(obj -> {
            obj.writeField(90, () -> obj.writeUleb(VectorType.CONSTANT.wireId()));
            obj.writeField(100, () -> obj.writeBool(false));
            obj.writeField(102, () -> obj.writeBlob(le32(42)));
        });
        DecodedVector v = decode(INT, 5, w.toByteArray());
        assertEquals(5, v.size());
        for (int i = 0; i < 5; i++) {
            assertEquals(42, ((Number) v.getObject(i)).intValue());
        }
    }

    @Test
    void sequenceVectorProducesArithmeticSeries() {
        BinaryWriter w = new BinaryWriter();
        w.writeObject(obj -> {
            obj.writeField(90, () -> obj.writeUleb(VectorType.SEQUENCE.wireId()));
            obj.writeField(91, () -> obj.writeSleb(100));
            obj.writeField(92, () -> obj.writeSleb(5));
        });
        DecodedVector v = decode(BIGINT, 4, w.toByteArray());
        assertEquals(4, v.size());
        assertEquals(100L, v.getLong(0));
        assertEquals(105L, v.getLong(1));
        assertEquals(110L, v.getLong(2));
        assertEquals(115L, v.getLong(3));
    }

    @Test
    void dictionaryVectorProjectsSelection() {
        BinaryWriter w = new BinaryWriter();
        w.writeObject(obj -> {
            obj.writeField(90, () -> obj.writeUleb(VectorType.DICTIONARY.wireId()));
            obj.writeField(91, () -> obj.writeBlob(le32(2, 0, 1, 2)));
            obj.writeField(92, () -> obj.writeUleb(3));
            obj.writeField(100, () -> obj.writeBool(false));
            obj.writeField(102, () -> obj.writeBlob(le32(10, 20, 30)));
        });
        DecodedVector v = decode(INT, 4, w.toByteArray());
        assertEquals(4, v.size());
        assertEquals(30, ((Number) v.getObject(0)).intValue());
        assertEquals(10, ((Number) v.getObject(1)).intValue());
        assertEquals(20, ((Number) v.getObject(2)).intValue());
        assertEquals(30, ((Number) v.getObject(3)).intValue());
    }

    @Test
    void unsignedFlatAndDictionaryValuesPreserveTheHighBitAndNulls() {
        Object[] values = {BigInteger.ZERO, BigInteger.valueOf(Long.MAX_VALUE),
                BigInteger.ONE.shiftLeft(63), BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE), null};
        for (boolean dictionary : new boolean[]{false, true}) {
            int[] selection = dictionary ? new int[]{3, 2, 0, 4, 1, 3} : new int[]{0, 1, 2, 3, 4};
            BinaryWriter w = new BinaryWriter();
            w.writeObject(obj -> {
                if (dictionary) {
                    obj.writeField(90, () -> obj.writeUleb(VectorType.DICTIONARY.wireId()));
                    obj.writeField(91, () -> obj.writeBlob(le32(selection)));
                    obj.writeField(92, () -> obj.writeUleb(values.length));
                }
                obj.writeField(100, () -> obj.writeBool(true));
                obj.writeField(101, () -> obj.writeBlob(new byte[]{15, 0, 0, 0, 0, 0, 0, 0}));
                obj.writeField(102, () -> obj.writeBlob(le64(0, Long.MAX_VALUE, Long.MIN_VALUE, -1, -1)));
            });
            DecodedVector v = decode(UBIGINT, selection.length, w.toByteArray());
            assertInstanceOf(DecodedVector.ObjectVec.class, v);
            assertEquals(selection.length, v.size());
            for (int i = 0; i < selection.length; i++) {
                assertEquals(values[selection[i]], v.getObject(i));
                assertEquals(values[selection[i]] == null, v.isNull(i));
            }
        }
    }

    @Test
    void unsignedConstantValuesAlwaysUseBigIntegerOrNull() {
        long[] bits = {0, Long.MAX_VALUE, Long.MIN_VALUE, -1};
        BigInteger[] values = {BigInteger.ZERO, BigInteger.valueOf(Long.MAX_VALUE),
                BigInteger.ONE.shiftLeft(63), BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE)};
        for (int i = 0; i < bits.length; i++) {
            long raw = bits[i];
            for (boolean valid : new boolean[]{true, false}) {
                BinaryWriter w = new BinaryWriter();
                w.writeObject(obj -> {
                    obj.writeField(90, () -> obj.writeUleb(VectorType.CONSTANT.wireId()));
                    obj.writeField(100, () -> obj.writeBool(!valid));
                    if (!valid) obj.writeField(101, () -> obj.writeBlob(new byte[8]));
                    obj.writeField(102, () -> obj.writeBlob(le64(raw)));
                });
                DecodedVector v = decode(UBIGINT, 3, w.toByteArray());
                assertInstanceOf(DecodedVector.ObjectVec.class, v);
                for (int row = 0; row < 3; row++) assertEquals(valid ? values[i] : null, v.getObject(row));
            }
        }
    }

    @Test
    void unsignedSequencesCrossTheSignedBoundaryWithoutChangingSign() {
        for (long[] sequence : new long[][]{{Long.MAX_VALUE - 1, 1}, {-1, -1}}) {
            BinaryWriter w = new BinaryWriter();
            w.writeObject(obj -> {
                obj.writeField(90, () -> obj.writeUleb(VectorType.SEQUENCE.wireId()));
                obj.writeField(91, () -> obj.writeSleb(sequence[0]));
                obj.writeField(92, () -> obj.writeSleb(sequence[1]));
            });
            DecodedVector v = decode(UBIGINT, 4, w.toByteArray());
            BigInteger start = new BigInteger(Long.toUnsignedString(sequence[0]));
            for (int i = 0; i < 4; i++) {
                assertEquals(start.add(BigInteger.valueOf(sequence[1] * i)), v.getObject(i));
            }
        }
    }

    @Test
    void emptyUnsignedVectorRemainsEmpty() {
        BinaryWriter w = new BinaryWriter();
        w.writeObject(obj -> {
            obj.writeField(100, () -> obj.writeBool(false));
            obj.writeField(102, () -> obj.writeBlob(new byte[0]));
        });
        DecodedVector v = decode(UBIGINT, 0, w.toByteArray());
        assertInstanceOf(DecodedVector.ObjectVec.class, v);
        assertEquals(0, v.size());
    }

    @Test
    void nullFixedWidthSlotsAreNotConvertedInFlatConstantOrDictionaryVectors() {
        LocalDateTime epoch = LocalDateTime.of(1970, 1, 1, 0, 0);
        Object[][] cases = {
                {LogicalType.of(LogicalTypeId.TIME), LocalTime.MIDNIGHT},
                {LogicalType.of(LogicalTypeId.TIME_NS), LocalTime.MIDNIGHT},
                {LogicalType.of(LogicalTypeId.TIMESTAMP_SEC), epoch},
                {LogicalType.of(LogicalTypeId.TIMESTAMP_MS), epoch},
                {LogicalType.of(LogicalTypeId.TIMESTAMP), epoch},
                {LogicalType.of(LogicalTypeId.TIMESTAMP_NS), epoch},
                {LogicalType.of(LogicalTypeId.TIMESTAMP_TZ), OffsetDateTime.parse("1970-01-01T00:00:00Z")},
                {LogicalType.of(LogicalTypeId.DATE), LocalDate.ofEpochDay(0)},
                {LogicalType.decimal(4, 2), new BigDecimal("0.00")},
                {LogicalType.decimal(9, 2), new BigDecimal("0.00")},
                {LogicalType.decimal(18, 2), new BigDecimal("0.00")},
                {LogicalType.decimal(38, 2), new BigDecimal("0.00")},
                {LogicalType.of(LogicalTypeId.ENUM,
                        new ExtraTypeInfo.EnumInfo(List.of("zero"), Optional.empty())), "zero"},
                {LogicalType.of(LogicalTypeId.UUID), new UUID(Long.MIN_VALUE, 0)},
                {LogicalType.of(LogicalTypeId.INTERVAL), new IntervalValue(0, 0, 0)},
                {UBIGINT, BigInteger.ZERO},
                {LogicalType.of(LogicalTypeId.HUGEINT), BigInteger.ZERO},
                {LogicalType.of(LogicalTypeId.UHUGEINT), BigInteger.ZERO}
        };
        for (Object[] sample : cases) {
            LogicalType type = (LogicalType) sample[0];
            int width = PhysicalTypeUtil.getPhysicalType(type).byteWidth();
            for (VectorType encoding : new VectorType[]{VectorType.FLAT, VectorType.CONSTANT, VectorType.DICTIONARY}) {
                int storedRows = encoding == VectorType.CONSTANT ? 1 : 4;
                int[] selection = encoding == VectorType.DICTIONARY ? new int[]{1, 0, 3, 2, 1} : new int[]{0, 1, 2, 3};
                byte[] payload = new byte[width * storedRows];
                // Null payloads are unspecified. Use signed-min sentinels, including Long.MIN_VALUE.
                for (int row = 0; row < storedRows; row += 2) payload[row * width + width - 1] = (byte) 0x80;
                BinaryWriter w = new BinaryWriter();
                w.writeObject(obj -> {
                    obj.writeField(90, () -> obj.writeUleb(encoding.wireId()));
                    if (encoding == VectorType.DICTIONARY) {
                        obj.writeField(91, () -> obj.writeBlob(le32(selection)));
                        obj.writeField(92, () -> obj.writeUleb(storedRows));
                    }
                    obj.writeField(100, () -> obj.writeBool(true));
                    obj.writeField(101, () -> obj.writeBlob(new byte[]{
                            (byte) (encoding == VectorType.CONSTANT ? 0 : 10), 0, 0, 0, 0, 0, 0, 0}));
                    obj.writeField(102, () -> obj.writeBlob(payload));
                });
                BinaryReader reader = new BinaryReader(w.toByteArray());
                DecodedVector vector = VectorCodec.decodeVector(reader, type, selection.length);
                reader.assertEof();
                assertEquals(selection.length, vector.size());
                for (int row = 0; row < selection.length; row++) {
                    boolean valid = encoding != VectorType.CONSTANT && selection[row] % 2 == 1;
                    assertEquals(valid ? sample[1] : null, vector.getObject(row), type.id() + " " + encoding);
                    assertEquals(!valid, vector.isNull(row));
                }
            }
        }
    }

    @Test
    void nullValidityDoesNotPermitTruncatedFixedWidthPayloads() {
        BinaryWriter w = new BinaryWriter();
        w.writeObject(obj -> {
            obj.writeField(100, () -> obj.writeBool(true));
            obj.writeField(101, () -> obj.writeBlob(new byte[8]));
            obj.writeField(102, () -> obj.writeBlob(new byte[7]));
        });
        QuackProtocolException error = assertThrows(QuackProtocolException.class,
                () -> decode(LogicalType.of(LogicalTypeId.TIME), 1, w.toByteArray()));
        assertTrue(error.getMessage().contains("expected 8"));
    }

    @Test
    void invalidNonNullValuesAreStillConvertedAndRejected() {
        LogicalType type = LogicalType.of(LogicalTypeId.ENUM,
                new ExtraTypeInfo.EnumInfo(List.of("zero"), Optional.empty()));
        BinaryWriter w = new BinaryWriter();
        w.writeObject(obj -> {
            obj.writeField(100, () -> obj.writeBool(false));
            obj.writeField(102, () -> obj.writeBlob(new byte[]{(byte) 0x80}));
        });
        assertThrows(QuackProtocolException.class, () -> decode(type, 1, w.toByteArray()));
    }

    @Test
    void structEncodeRejectsNonMapRow() {
        // A non-null STRUCT row that is not a Map must fail loudly rather than
        // silently encoding every field as NULL in the bulk-load path.
        LogicalType structType = LogicalType.of(LogicalTypeId.STRUCT,
                new ExtraTypeInfo.StructInfo(List.of(new ChildType("x", INT)), Optional.empty()));
        DecodedVector vector = new DecodedVector.ObjectVec(structType, new Object[]{"not-a-map"});
        QuackProtocolException ex = assertThrows(QuackProtocolException.class,
                () -> VectorCodec.encodeVector(new BinaryWriter(), structType, vector));
        assertTrue(ex.getMessage().contains("map value for STRUCT"),
                "unexpected message: " + ex.getMessage());
    }

    @Test
    void fsstVectorIsRejectedWithClearError() {
        BinaryWriter w = new BinaryWriter();
        w.writeObject(obj -> obj.writeField(90, () -> obj.writeUleb(VectorType.FSST.wireId())));
        QuackUnsupportedTypeException ex = assertThrows(QuackUnsupportedTypeException.class,
                () -> decode(INT, 3, w.toByteArray()));
        assertEquals(true, ex.getMessage().toUpperCase().contains("FSST"));
    }
}

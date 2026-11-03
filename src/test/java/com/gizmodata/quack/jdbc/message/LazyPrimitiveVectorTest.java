package com.gizmodata.quack.jdbc.message;

import com.gizmodata.quack.jdbc.QuackProtocolException;
import com.gizmodata.quack.jdbc.codec.BinaryReader;
import com.gizmodata.quack.jdbc.codec.BinaryWriter;
import com.gizmodata.quack.jdbc.codec.DecodeLimits;
import com.gizmodata.quack.jdbc.type.LogicalType;
import com.gizmodata.quack.jdbc.type.LogicalTypeId;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

public class LazyPrimitiveVectorTest {
    private static final LogicalType INT = LogicalType.of(LogicalTypeId.INTEGER);
    private static final LogicalType BIGINT = LogicalType.of(LogicalTypeId.BIGINT);

    @Test
    void hugeSequencesFitSmallBudgetsAndPreserveBoxedTypesAndRandomAccess() {
        for (LogicalType type : List.of(INT, BIGINT)) {
            DecodedVector vector = decode(sequence(-100, 3), type, Integer.MAX_VALUE, 256);
            for (int row : new int[]{0, 1, 39_999_999, Integer.MAX_VALUE - 1}) {
                long value = -100L + 3L * row;
                if (type == INT) {
                    assertInstanceOf(Integer.class, vector.getObject(row));
                    assertEquals((int) value, vector.getObject(row));
                    assertEquals((long) (int) value, vector.getLong(row));
                } else {
                    assertInstanceOf(Long.class, vector.getObject(row));
                    assertEquals(value, vector.getObject(row));
                    assertEquals(value, vector.getLong(row));
                }
                assertFalse(vector.isNull(row));
            }
            assertBounds(vector);
            assertThrows(QuackProtocolException.class,
                    () -> decode(sequence(-100, 3), type, Integer.MAX_VALUE, 255));
        }
    }

    @Test
    void integerSequencesKeepExistingNarrowingAndTwosComplementArithmetic() {
        for (long[] values : new long[][]{{Long.MAX_VALUE - 1, 1}, {Long.MIN_VALUE + 2, Long.MAX_VALUE - 1},
                {Integer.MAX_VALUE, 1}, {100, -7}, {Long.MIN_VALUE, -1}, {42, 0}}) {
            for (LogicalType type : List.of(INT, BIGINT)) {
                DecodedVector vector = decode(sequence(values[0], values[1]), type, 5, 256);
                long current = values[0];
                for (int row = 0; row < 5; row++) {
                    Object expected;
                    if (type == INT) expected = (int) current;
                    else expected = current;
                    assertEquals(expected, vector.getObject(row));
                    assertEquals(((Number) expected).intValue(), vector.getInt(row));
                    assertEquals(((Number) expected).doubleValue(), vector.getDouble(row));
                    current += values[1];
                }
            }
        }
    }

    @Test
    void primitiveConstantsIncludingNullsUseConstantSpaceWithExactJavaTypes() {
        for (Object[] sample : primitiveSamples()) {
            LogicalType type = LogicalType.of((LogicalTypeId) sample[0]);
            Object value = sample[1];
            for (boolean isNull : new boolean[]{false, true}) {
                byte[] wire = constant(type, isNull ? null : value);
                DecodedVector vector = decode(wire, type, Integer.MAX_VALUE, 1024);
                assertEquals(Integer.MAX_VALUE, vector.size());
                for (int row : new int[]{0, 65, Integer.MAX_VALUE - 1}) {
                    assertEquals(isNull ? null : value, vector.getObject(row));
                    assertEquals(isNull, vector.isNull(row));
                    if (isNull) {
                        assertEquals(0L, vector.getLong(row));
                        assertEquals(0d, vector.getDouble(row));
                    } else if (value instanceof Number number) {
                        assertEquals(value.getClass(), vector.getObject(row).getClass());
                        assertEquals(number.longValue(), vector.getLong(row));
                        assertEquals(number.doubleValue(), vector.getDouble(row));
                    } else assertEquals(value, vector.getBoolean(row));
                }
                assertBounds(vector);
            }
        }
    }

    @Test
    void compactVectorsStillValidateEmptyNegativeTruncatedAndNestedInputs() {
        for (byte[] wire : new byte[][]{sequence(0, 1), constant(INT, null, 0)}) {
            DecodedVector vector = decode(wire, INT, 0, 1024);
            assertEquals(0, vector.size());
            assertBounds(vector);
            assertThrows(QuackProtocolException.class, () -> decode(wire, INT, -1, 1024));
        }
        byte[] truncated = sequence(0, 1);
        assertThrows(QuackProtocolException.class, () -> decode(java.util.Arrays.copyOf(truncated,
                truncated.length - 1), BIGINT, 40_000_000, 1024));
        BinaryWriter nested = new BinaryWriter();
        nested.writeObject(obj -> {
            for (int i = 0; i < 100; i++) obj.writeField(90, () -> obj.writeUleb(VectorType.CONSTANT.wireId()));
            obj.writeField(90, () -> obj.writeUleb(VectorType.SEQUENCE.wireId()));
            obj.writeField(91, () -> obj.writeSleb(1));
            obj.writeField(92, () -> obj.writeSleb(0));
        });
        QuackProtocolException error = assertThrows(QuackProtocolException.class,
                () -> decode(nested.toByteArray(), INT, 1, 100_000));
        assertTrue(error.getMessage().contains("maxNestingDepth"));
    }

    @Test
    void flatObjectAndDictionaryAllocationsAreStillBudgeted() {
        BinaryWriter flat = new BinaryWriter();
        VectorCodec.encodeVector(flat, INT, new DecodedVector.IntVec(INT, new int[]{1}, null));
        assertThrows(QuackProtocolException.class, () -> decode(flat.toByteArray(), INT, 40_000_000, 1024));
        assertThrows(QuackProtocolException.class, () -> decode(sequence(0, 1),
                LogicalType.of(LogicalTypeId.DATE), 40_000_000, 1024));
        BinaryWriter dictionary = new BinaryWriter();
        dictionary.writeObject(obj -> obj.writeField(90, () -> obj.writeUleb(VectorType.DICTIONARY.wireId())));
        assertThrows(QuackProtocolException.class, () -> decode(dictionary.toByteArray(), INT, 40_000_000, 1024));
        byte[] a = sequence(0, 1);
        byte[] both = new byte[a.length * 2];
        System.arraycopy(a, 0, both, 0, a.length);
        System.arraycopy(a, 0, both, a.length, a.length);
        BinaryReader reader = new BinaryReader(both, new DecodeLimits(1024, 511, 64));
        VectorCodec.decodeVector(reader, INT, 40_000_000);
        assertThrows(QuackProtocolException.class, () -> VectorCodec.decodeVector(reader, INT, 40_000_000));
    }

    @Test
    void compactSourcesKeepDictionaryProjectionsTypedAndBudgeted() {
        for (LogicalType type : List.of(INT, BIGINT)) {
            for (byte[] source : new byte[][]{sequence(10, 2), constant(type, type == INT ? (Object) 7 : 7L),
                    constant(type, null)}) {
                BinaryWriter selection = new BinaryWriter();
                for (int index : new int[]{2, 0, 2, 1}) selection.writeFixedInt32(index);
                BinaryWriter writer = new BinaryWriter();
                writer.writeObject(obj -> {
                    obj.writeField(90, () -> obj.writeUleb(VectorType.DICTIONARY.wireId()));
                    obj.writeField(91, () -> obj.writeBlob(selection.toByteArray()));
                    obj.writeField(92, () -> obj.writeUleb(3));
                    obj.writeBytes(java.util.Arrays.copyOf(source, source.length - 2));
                });
                DecodedVector dictionary = decode(source, type, 3, 1024);
                DecodedVector projected = decode(writer.toByteArray(), type, 4, 4096);
                assertTrue(type == INT ? projected instanceof DecodedVector.IntVec
                        : projected instanceof DecodedVector.LongVec);
                int[] indices = {2, 0, 2, 1};
                for (int row = 0; row < indices.length; row++) {
                    assertEquals(dictionary.getObject(indices[row]), projected.getObject(row));
                    assertEquals(dictionary.isNull(indices[row]), projected.isNull(row));
                }
            }
        }
    }

    @Test
    void constantsNormalizeNumericSequenceSourcesToTheirOriginalWrapperTypes() {
        Object[][] cases = {{LogicalTypeId.TINYINT, (byte) -7}, {LogicalTypeId.SMALLINT, (short) -7},
                {LogicalTypeId.UTINYINT, (short) -7}, {LogicalTypeId.USMALLINT, -7},
                {LogicalTypeId.FLOAT, -7f}, {LogicalTypeId.DOUBLE, -7d}};
        BinaryWriter writer = new BinaryWriter();
        byte[] source = sequence(-7, 0);
        writer.writeObject(obj -> {
            obj.writeField(90, () -> obj.writeUleb(VectorType.CONSTANT.wireId()));
            obj.writeBytes(java.util.Arrays.copyOf(source, source.length - 2));
        });
        for (Object[] sample : cases) {
            LogicalType type = LogicalType.of((LogicalTypeId) sample[0]);
            DecodedVector vector = decode(writer.toByteArray(), type, 100_000, 2048);
            assertEquals(sample[1], vector.getObject(99_999));
            assertEquals(sample[1].getClass(), vector.getObject(99_999).getClass());
        }
    }

    @Test
    void compactValuesRoundTripThroughTheFlatAppendCodecIncludingNullMasks() {
        for (long version : new long[]{1, 3}) {
            for (Object[] sample : primitiveSamples()) {
                LogicalType type = LogicalType.of((LogicalTypeId) sample[0]);
                for (boolean nullValue : new boolean[]{false, true}) {
                    DecodedVector compact = decode(constant(type, nullValue ? null : sample[1]), type, 65, 1024);
                    BinaryWriter writer = new BinaryWriter(1024, version);
                    VectorCodec.encodeVector(writer, type, compact);
                    BinaryReader reader = new BinaryReader(writer.toByteArray(), DecodeLimits.DEFAULT, version);
                    DecodedVector flat = VectorCodec.decodeVector(reader, type, 65);
                    reader.assertEof();
                    for (int row = 0; row < 65; row++) {
                        assertEquals(compact.getObject(row), flat.getObject(row));
                        assertEquals(compact.isNull(row), flat.isNull(row));
                    }
                }
            }
            for (LogicalType type : List.of(INT, BIGINT)) {
                DecodedVector compact = decode(sequence(-10, 2), type, 65, 256);
                BinaryWriter writer = new BinaryWriter(1024, version);
                VectorCodec.encodeVector(writer, type, compact);
                DecodedVector flat = VectorCodec.decodeVector(new BinaryReader(writer.toByteArray(),
                        DecodeLimits.DEFAULT, version), type, 65);
                for (int row = 0; row < 65; row++) assertEquals(compact.getObject(row), flat.getObject(row));
            }
        }
    }

    @Test
    void objectConstantsRemainEagerAndPublicDescriptorsRejectInvalidTypesAndValues() {
        for (Object[] sample : new Object[][]{{LogicalType.of(LogicalTypeId.BLOB), new byte[]{1, 2}},
                {LogicalType.of(LogicalTypeId.VARCHAR), "value"},
                {LogicalType.of(LogicalTypeId.UBIGINT), java.math.BigInteger.ONE}}) {
            LogicalType type = (LogicalType) sample[0];
            byte[] wire = constant(type, sample[1]);
            assertInstanceOf(DecodedVector.ObjectVec.class, decode(wire, type, 3, 4096));
            assertThrows(QuackProtocolException.class, () -> decode(wire, type, Integer.MAX_VALUE, 4096));
        }
        assertThrows(IllegalArgumentException.class, () -> new DecodedVector.IntSequenceVec(BIGINT, 1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new DecodedVector.LongSequenceVec(INT, 1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new DecodedVector.LongSequenceVec(BIGINT, -1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new DecodedVector.ConstantVec(INT, 1, 1L));
        assertThrows(IllegalArgumentException.class, () -> new DecodedVector.ConstantVec(INT, -1, 1));
        assertThrows(IllegalArgumentException.class, () -> new DecodedVector.ConstantVec(
                LogicalType.of(LogicalTypeId.BLOB), 1, new byte[0]));
    }

    @Test
    void largeCompressedVectorsFitAFixedHeapSubprocess() throws Exception {
        Process child = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xmx32m", "-XX:+ExitOnOutOfMemoryError", "-cp",
                System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                LazyPrimitiveVectorTest.class.getName()).redirectErrorStream(true).start();
        try {
            assertTrue(child.waitFor(20, TimeUnit.SECONDS), "fixed-heap decoder timed out");
            String output = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, child.exitValue(), output);
            assertTrue(output.contains("compact vectors fit 32 MiB heap"), output);
        } finally {
            child.destroyForcibly();
            child.waitFor(5, TimeUnit.SECONDS);
        }
    }

    public static void main(String[] args) {
        DecodedVector sequence = decode(sequence(0, 1), BIGINT, 40_000_000, 1024);
        DecodedVector constant = decode(constant(INT, 42), INT, Integer.MAX_VALUE, 1024);
        if (sequence.getLong(39_999_999) != 39_999_999L || constant.getInt(Integer.MAX_VALUE - 1) != 42) {
            throw new AssertionError("wrong compact values");
        }
        System.out.println("compact vectors fit 32 MiB heap");
    }

    private static List<Object[]> primitiveSamples() {
        return List.of(new Object[]{LogicalTypeId.BOOLEAN, true}, new Object[]{LogicalTypeId.TINYINT, (byte) -128},
                new Object[]{LogicalTypeId.UTINYINT, (short) 255}, new Object[]{LogicalTypeId.SMALLINT, (short) -32768},
                new Object[]{LogicalTypeId.USMALLINT, 65535}, new Object[]{LogicalTypeId.INTEGER, Integer.MIN_VALUE},
                new Object[]{LogicalTypeId.UINTEGER, 4294967295L}, new Object[]{LogicalTypeId.BIGINT, Long.MIN_VALUE},
                new Object[]{LogicalTypeId.FLOAT, -0.0f}, new Object[]{LogicalTypeId.DOUBLE, Double.NaN});
    }

    private static void assertBounds(DecodedVector vector) {
        for (int row : new int[]{-1, vector.size()}) {
            assertThrows(IndexOutOfBoundsException.class, () -> vector.getObject(row));
            assertThrows(IndexOutOfBoundsException.class, () -> vector.isNull(row));
            assertThrows(IndexOutOfBoundsException.class, () -> vector.getLong(row));
        }
    }

    private static byte[] sequence(long start, long step) {
        BinaryWriter writer = new BinaryWriter();
        writer.writeObject(obj -> {
            obj.writeField(90, () -> obj.writeUleb(VectorType.SEQUENCE.wireId()));
            obj.writeField(91, () -> obj.writeSleb(start));
            obj.writeField(92, () -> obj.writeSleb(step));
        });
        return writer.toByteArray();
    }

    private static byte[] constant(LogicalType type, Object value) {
        return constant(type, value, 1);
    }

    private static byte[] constant(LogicalType type, Object value, int sourceCount) {
        BinaryWriter flat = new BinaryWriter();
        VectorCodec.encodeVector(flat, type, new DecodedVector.ObjectVec(type,
                sourceCount == 0 ? new Object[0] : new Object[]{value}));
        byte[] payload = flat.toByteArray();
        BinaryWriter writer = new BinaryWriter();
        writer.writeObject(obj -> {
            obj.writeField(90, () -> obj.writeUleb(VectorType.CONSTANT.wireId()));
            obj.writeBytes(java.util.Arrays.copyOf(payload, payload.length - 2));
        });
        return writer.toByteArray();
    }

    private static DecodedVector decode(byte[] wire, LogicalType type, int count, long budget) {
        BinaryReader reader = new BinaryReader(wire, new DecodeLimits(1_000_000, budget, 64));
        DecodedVector vector = VectorCodec.decodeVector(reader, type, count);
        reader.assertEof();
        return vector;
    }
}

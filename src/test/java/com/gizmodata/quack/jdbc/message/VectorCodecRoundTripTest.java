package com.gizmodata.quack.jdbc.message;

import com.gizmodata.quack.jdbc.QuackProtocolException;
import com.gizmodata.quack.jdbc.codec.BinaryReader;
import com.gizmodata.quack.jdbc.codec.BinaryWriter;
import com.gizmodata.quack.jdbc.type.ChildType;
import com.gizmodata.quack.jdbc.type.ExtraTypeInfo;
import com.gizmodata.quack.jdbc.type.LogicalType;
import com.gizmodata.quack.jdbc.type.LogicalTypeId;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-trip tests for {@link VectorCodec} encode/decode. Builds a
 * DataChunk in code, encodes it, decodes the bytes back, and verifies
 * the values match — pure unit tests, no Quack server required.
 */
class VectorCodecRoundTripTest {

    @Test
    void unnamedStructEncodingPreservesPositionsAndRejectsAmbiguousValues() {
        LogicalType integer = LogicalType.of(LogicalTypeId.INTEGER);
        LogicalType tuple = LogicalType.of(LogicalTypeId.STRUCT, new ExtraTypeInfo.StructInfo(
                List.of(new ChildType("", integer), new ChildType("", integer)), Optional.empty()));
        Object[] values = {List.of(1, 2), null, Arrays.asList(null, 3), Arrays.asList(null, null)};
        DataChunk chunk = new DataChunk(values.length, List.of(tuple),
                List.of(new DecodedVector.ObjectVec(tuple, values)));
        DataChunk decoded = roundTrip(chunk);
        for (int row = 0; row < values.length; row++) assertEquals(values[row], decoded.columns().get(0).getObject(row));
        for (Object invalid : new Object[]{Map.of("", 1), List.of(1), List.of(1, 2, 3), "tuple"}) {
            assertThrows(QuackProtocolException.class, () -> encodeValue(tuple, invalid));
        }
    }

    @Test
    void integerVarcharChunkRoundTrips() {
        LogicalType intType = LogicalType.of(LogicalTypeId.INTEGER);
        LogicalType varcharType = LogicalType.of(LogicalTypeId.VARCHAR);
        DataChunk chunk = new DataChunk(3,
                List.of(intType, varcharType),
                List.of(
                        new DecodedVector.IntVec(intType, new int[]{1, 2, 3}, null),
                        new DecodedVector.ObjectVec(varcharType, new Object[]{"alpha", "beta", "gamma"})));

        DataChunk decoded = roundTrip(chunk);
        assertEquals(3, decoded.rowCount());
        assertEquals(2, decoded.columns().size());
        for (int row = 0; row < 3; row++) {
            assertEquals(row + 1, ((Number) decoded.columns().get(0).getObject(row)).intValue());
        }
        assertEquals("alpha", decoded.columns().get(1).getObject(0));
        assertEquals("beta", decoded.columns().get(1).getObject(1));
        assertEquals("gamma", decoded.columns().get(1).getObject(2));
    }

    @Test
    void nullsRoundTripViaValidityBitmap() {
        LogicalType bigintType = LogicalType.of(LogicalTypeId.BIGINT);
        long[] validity = Validity.allValid(4);
        Validity.setNull(validity, 1);
        Validity.setNull(validity, 3);
        DataChunk chunk = new DataChunk(4, List.of(bigintType), List.of(
                new DecodedVector.LongVec(bigintType, new long[]{10L, 0L, 30L, 0L}, validity)));

        DataChunk decoded = roundTrip(chunk);
        assertEquals(10L, decoded.columns().get(0).getLong(0));
        assertTrue(decoded.columns().get(0).isNull(1));
        assertEquals(30L, decoded.columns().get(0).getLong(2));
        assertTrue(decoded.columns().get(0).isNull(3));
    }

    @Test
    void scalarMixRoundTrips() {
        LogicalType boolType = LogicalType.of(LogicalTypeId.BOOLEAN);
        LogicalType doubleType = LogicalType.of(LogicalTypeId.DOUBLE);
        LogicalType dateType = LogicalType.of(LogicalTypeId.DATE);
        LogicalType tsType = LogicalType.of(LogicalTypeId.TIMESTAMP);
        LogicalType decType = LogicalType.decimal(10, 2);
        LogicalType uuidType = LogicalType.of(LogicalTypeId.UUID);

        UUID u = UUID.fromString("01234567-89ab-cdef-0123-456789abcdef");
        LocalDate d = LocalDate.of(2026, 5, 13);
        LocalDateTime ts = LocalDateTime.of(2026, 5, 13, 14, 30, 0);

        DataChunk chunk = new DataChunk(1,
                List.of(boolType, doubleType, dateType, tsType, decType, uuidType),
                List.of(
                        new DecodedVector.BoolVec(boolType, new boolean[]{true}, null),
                        new DecodedVector.DoubleVec(doubleType, new double[]{3.14}, null),
                        new DecodedVector.ObjectVec(dateType, new Object[]{d}),
                        new DecodedVector.ObjectVec(tsType, new Object[]{ts}),
                        new DecodedVector.ObjectVec(decType, new Object[]{new BigDecimal("12.34")}),
                        new DecodedVector.ObjectVec(uuidType, new Object[]{u})));

        DataChunk decoded = roundTrip(chunk);
        assertEquals(true, decoded.columns().get(0).getObject(0));
        assertEquals(3.14, decoded.columns().get(1).getDouble(0), 1e-9);
        assertEquals(d, decoded.columns().get(2).getObject(0));
        assertEquals(ts, decoded.columns().get(3).getObject(0));
        assertEquals(new BigDecimal("12.34"), decoded.columns().get(4).getObject(0));
        assertEquals(u, decoded.columns().get(5).getObject(0));
    }

    @Test
    void emptyChunkRoundTrips() {
        LogicalType intType = LogicalType.of(LogicalTypeId.INTEGER);
        DataChunk chunk = new DataChunk(0, List.of(intType), List.of(
                new DecodedVector.IntVec(intType, new int[0], null)));
        DataChunk decoded = roundTrip(chunk);
        assertEquals(0, decoded.rowCount());
        assertEquals(0, decoded.columns().get(0).size());
    }

    @Test
    void allNullColumnRoundTrips() {
        LogicalType intType = LogicalType.of(LogicalTypeId.INTEGER);
        long[] validity = new long[Validity.wordCount(3)]; // all zeros = all null
        DataChunk chunk = new DataChunk(3, List.of(intType), List.of(
                new DecodedVector.IntVec(intType, new int[3], validity)));
        DataChunk decoded = roundTrip(chunk);
        for (int i = 0; i < 3; i++) {
            assertNull(decoded.columns().get(0).getObject(i));
        }
    }

    @Test
    void integerEncodingRejectsOverflowInsteadOfTruncating() {
        LogicalTypeId[] signed = {LogicalTypeId.TINYINT, LogicalTypeId.SMALLINT,
                LogicalTypeId.INTEGER, LogicalTypeId.BIGINT, LogicalTypeId.HUGEINT};
        LogicalTypeId[] unsigned = {LogicalTypeId.UTINYINT, LogicalTypeId.USMALLINT,
                LogicalTypeId.UINTEGER, LogicalTypeId.UBIGINT, LogicalTypeId.UHUGEINT};
        int[] bits = {8, 16, 32, 64, 128};
        for (int i = 0; i < bits.length; i++) {
            LogicalType signedType = LogicalType.of(signed[i]);
            LogicalType unsignedType = LogicalType.of(unsigned[i]);
            BigInteger signedLimit = BigInteger.ONE.shiftLeft(bits[i] - 1);
            BigInteger unsignedLimit = BigInteger.ONE.shiftLeft(bits[i]);
            assertThrows(QuackProtocolException.class, () -> encodeValue(signedType, signedLimit));
            assertThrows(QuackProtocolException.class,
                    () -> encodeValue(signedType, signedLimit.negate().subtract(BigInteger.ONE)));
            assertThrows(QuackProtocolException.class, () -> encodeValue(unsignedType, unsignedLimit));
            assertThrows(QuackProtocolException.class, () -> encodeValue(unsignedType, BigInteger.valueOf(-1)));
            for (Object invalid : new Object[]{new BigDecimal("1.5"), Double.NaN, Double.POSITIVE_INFINITY}) {
                assertThrows(QuackProtocolException.class, () -> encodeValue(signedType, invalid));
            }

            // Assert wire bits independently of logical value materialization.
            BigInteger max = unsignedLimit.subtract(BigInteger.ONE);
            for (Object[] sample : new Object[][]{
                    {signedType, signedLimit.negate()}, {signedType, signedLimit.subtract(BigInteger.ONE)},
                    {unsignedType, max}, {unsignedType, BigInteger.ZERO}}) {
                BinaryReader reader = new BinaryReader(encodeValue((LogicalType) sample[0], sample[1]));
                assertEquals(false, reader.readRequiredField(100, reader::readBool));
                byte[] raw = reader.readRequiredField(102, reader::readBlob);
                reader.readEndObject();
                reader.assertEof();
                BigInteger expected = (BigInteger) sample[1];
                assertEquals(bits[i] / 8, raw.length);
                for (int b = 0; b < raw.length; b++) {
                    assertEquals(expected.shiftRight(b * 8).byteValue(), raw[b]);
                }
            }
        }
    }

    @Test
    void decimalEncodingChecksDeclaredPrecisionAndRoundingCarry() {
        for (int width : new int[]{1, 4, 5, 9, 10, 18, 19, 38}) {
            for (int scale : new int[]{0, width}) {
                LogicalType type = LogicalType.decimal(width, scale);
                BigDecimal limit = new BigDecimal(BigInteger.TEN.pow(width), scale);
                assertThrows(QuackProtocolException.class, () -> encodeValue(type, limit));
                assertThrows(QuackProtocolException.class, () -> encodeValue(type, limit.negate()));
                BigDecimal carries = limit.subtract(BigDecimal.valueOf(4, scale + 1));
                assertThrows(QuackProtocolException.class, () -> encodeValue(type, carries));
                BigDecimal max = limit.subtract(BigDecimal.ONE.scaleByPowerOfTen(-scale));
                for (BigDecimal value : new BigDecimal[]{max, max.negate()}) {
                    DataChunk chunk = new DataChunk(1, List.of(type), List.of(
                            new DecodedVector.ObjectVec(type, new Object[]{value})));
                    assertEquals(value, roundTrip(chunk).columns().get(0).getObject(0));
                }
            }
        }
        assertThrows(QuackProtocolException.class,
                () -> encodeValue(LogicalType.decimal(4, 0), new BigDecimal("40000")));
        for (LogicalType invalid : new LogicalType[]{LogicalType.decimal(0, 0),
                LogicalType.decimal(39, 0), LogicalType.decimal(4, -1), LogicalType.decimal(4, 5)}) {
            assertThrows(QuackProtocolException.class, () -> encodeValue(invalid, null));
        }
    }

    @Test
    void integerEncodingUsesTheExactFloatingPointValue() {
        LogicalType type = LogicalType.of(LogicalTypeId.BIGINT);
        for (Number value : new Number[]{Math.scalb(1.0, 60), -Math.scalb(1.0, 63),
                Math.scalb(1.0f, 60), -Math.scalb(1.0f, 63)}) {
            DecodedVector decoded = VectorCodec.decodeVector(new BinaryReader(encodeValue(type, value)), type, 1);
            assertEquals(new BigDecimal(value.doubleValue()).longValueExact(), decoded.getLong(0));
        }
        assertThrows(QuackProtocolException.class, () -> encodeValue(type, Math.scalb(1.0f, 63)));
        assertThrows(QuackProtocolException.class, () -> encodeValue(type, Math.scalb(1.0, 63)));
        assertThrows(QuackProtocolException.class,
                () -> encodeValue(LogicalType.of(LogicalTypeId.UBIGINT), Math.scalb(1.0, 64)));
    }

    @Test
    void decimalEncodingPreservesExactIntegralInputs() {
        for (int scale : new int[]{0, 2}) {
            LogicalType type = LogicalType.decimal(18, scale);
            for (Number value : new Number[]{9_007_199_254_740_993L, -9_007_199_254_740_993L,
                    new BigInteger("9007199254740993"), new BigInteger("-9007199254740993")}) {
                BinaryReader reader = new BinaryReader(encodeValue(type, value));
                assertEquals(new BigDecimal(value.toString()).setScale(scale),
                        VectorCodec.decodeVector(reader, type, 1).getObject(0));
                reader.assertEof();
            }
        }
        LogicalType wide = LogicalType.decimal(38, 0);
        BigInteger value = new BigInteger("18446744073709551617");
        assertEquals(new BigDecimal(value),
                VectorCodec.decodeVector(new BinaryReader(encodeValue(wide, value)), wide, 1).getObject(0));
    }

    private static byte[] encodeValue(LogicalType type, Object value) {
        BinaryWriter writer = new BinaryWriter();
        VectorCodec.encodeVector(writer, type, new DecodedVector.ObjectVec(type, new Object[]{value}));
        return writer.toByteArray();
    }

    private static DataChunk roundTrip(DataChunk chunk) {
        BinaryWriter writer = new BinaryWriter();
        VectorCodec.encodeDataChunkWrapper(writer, chunk);
        BinaryReader reader = new BinaryReader(writer.toByteArray());
        DataChunk decoded = VectorCodec.decodeDataChunkWrapper(reader);
        assertNotNull(decoded);
        return decoded;
    }
}

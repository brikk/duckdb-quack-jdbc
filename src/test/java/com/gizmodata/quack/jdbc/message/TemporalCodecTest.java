package com.gizmodata.quack.jdbc.message;

import com.gizmodata.quack.jdbc.QuackProtocolException;
import com.gizmodata.quack.jdbc.QuackUnsupportedTypeException;
import com.gizmodata.quack.jdbc.codec.BinaryReader;
import com.gizmodata.quack.jdbc.codec.BinaryWriter;
import com.gizmodata.quack.jdbc.type.LogicalType;
import com.gizmodata.quack.jdbc.type.LogicalTypeId;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class TemporalCodecTest {
    private static final LogicalTypeId[] TIMESTAMPS = {LogicalTypeId.TIMESTAMP_SEC, LogicalTypeId.TIMESTAMP_MS,
            LogicalTypeId.TIMESTAMP, LogicalTypeId.TIMESTAMP_NS, LogicalTypeId.TIMESTAMP_TZ};

    @Test
    void temporalSpecialValuesAreRejectedAcrossEveryEncoding() {
        for (VectorType encoding : new VectorType[]{VectorType.FLAT, VectorType.CONSTANT, VectorType.DICTIONARY, VectorType.SEQUENCE}) {
            for (LogicalTypeId id : TIMESTAMPS) {
                for (long raw : new long[]{-Long.MAX_VALUE, Long.MAX_VALUE}) {
                    assertThrows(QuackUnsupportedTypeException.class, () -> decodeRaw(id, raw, encoding, true), id + " " + encoding);
                }
            }
            for (long raw : new long[]{-Integer.MAX_VALUE, Integer.MAX_VALUE}) {
                assertThrows(QuackUnsupportedTypeException.class, () -> decodeRaw(LogicalTypeId.DATE, raw, encoding, true));
            }
            for (LogicalTypeId id : new LogicalTypeId[]{LogicalTypeId.TIME, LogicalTypeId.TIME_NS}) {
                long day = id == LogicalTypeId.TIME ? 86_400_000_000L : 86_400_000_000_000L;
                for (long raw : new long[]{-1, day, day + 1, Long.MIN_VALUE, Long.MAX_VALUE}) {
                    assertThrows(QuackUnsupportedTypeException.class, () -> decodeRaw(id, raw, encoding, true));
                }
            }
            for (long raw : new long[]{Long.MIN_VALUE, Long.MAX_VALUE - 1,
                    LocalDateTime.MIN.toEpochSecond(ZoneOffset.UTC) - 1,
                    LocalDateTime.MAX.toEpochSecond(ZoneOffset.UTC) + 1}) {
                assertThrows(QuackUnsupportedTypeException.class,
                        () -> decodeRaw(LogicalTypeId.TIMESTAMP_SEC, raw, encoding, true));
            }
        }
    }

    @Test
    void temporalFiniteEndpointsRemainExactAcrossEveryEncoding() {
        for (VectorType encoding : new VectorType[]{VectorType.FLAT, VectorType.CONSTANT, VectorType.DICTIONARY, VectorType.SEQUENCE}) {
            for (LogicalTypeId id : TIMESTAMPS) {
                long[] samples = id == LogicalTypeId.TIMESTAMP_SEC
                        ? new long[]{LocalDateTime.MIN.toEpochSecond(ZoneOffset.UTC), 0, LocalDateTime.MAX.toEpochSecond(ZoneOffset.UTC)}
                        : new long[]{Long.MIN_VALUE, Long.MIN_VALUE + 2, 0, Long.MAX_VALUE - 1};
                for (long raw : samples) {
                    Instant instant = instant(BigInteger.valueOf(raw), units(id));
                    Object expected = id == LogicalTypeId.TIMESTAMP_TZ ? OffsetDateTime.ofInstant(instant, ZoneOffset.UTC)
                            : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
                    assertEquals(expected, decodeRaw(id, raw, encoding, true).getObject(0), id + " " + raw);
                }
            }
            for (long raw : new long[]{Integer.MIN_VALUE, Integer.MIN_VALUE + 2, 0, Integer.MAX_VALUE - 1}) {
                assertEquals(LocalDate.ofEpochDay(raw), decodeRaw(LogicalTypeId.DATE, raw, encoding, true).getObject(0));
            }
            for (LogicalTypeId id : new LogicalTypeId[]{LogicalTypeId.TIME, LogicalTypeId.TIME_NS}) {
                long scale = id == LogicalTypeId.TIME ? 1_000 : 1;
                for (long raw : new long[]{0, 86_400_000_000_000L / scale - 1}) {
                    assertEquals(LocalTime.ofNanoOfDay(raw * scale), decodeRaw(id, raw, encoding, true).getObject(0));
                }
            }
            assertEquals(-1L, decodeRaw(LogicalTypeId.TIME_TZ, -1, encoding, true).getObject(0));
        }
    }

    @Test
    void validityMaskedTemporalPayloadsAreStillIgnoredWithoutLosingAlignment() {
        for (VectorType encoding : new VectorType[]{VectorType.FLAT, VectorType.CONSTANT, VectorType.DICTIONARY}) {
            for (LogicalTypeId id : new LogicalTypeId[]{LogicalTypeId.DATE, LogicalTypeId.TIME, LogicalTypeId.TIME_NS,
                    LogicalTypeId.TIMESTAMP_SEC, LogicalTypeId.TIMESTAMP_MS, LogicalTypeId.TIMESTAMP,
                    LogicalTypeId.TIMESTAMP_NS, LogicalTypeId.TIMESTAMP_TZ}) {
                long infinity = id == LogicalTypeId.DATE ? Integer.MAX_VALUE : Long.MAX_VALUE;
                for (long raw : new long[]{infinity, -infinity, id == LogicalTypeId.DATE ? Integer.MIN_VALUE : Long.MIN_VALUE}) {
                    DecodedVector vector = decodeRaw(id, raw, encoding, false);
                    assertNull(vector.getObject(0));
                    assertTrue(vector.isNull(0));
                    if (encoding != VectorType.CONSTANT) assertNotNull(vector.getObject(1));
                }
            }
        }
    }

    @Test
    void temporalSequencesCheckEveryValueAndAvoidIntermediateProductOverflow() {
        LogicalType type = LogicalType.of(LogicalTypeId.TIMESTAMP_NS);
        DecodedVector v = decodeSequence(type, Long.MIN_VALUE + 2, Long.MAX_VALUE - 1, 3);
        for (int row = 0; row < 3; row++) {
            long raw = new long[]{Long.MIN_VALUE + 2, 0, Long.MAX_VALUE - 1}[row];
            assertEquals(LocalDateTime.ofInstant(instant(BigInteger.valueOf(raw), 1_000_000_000), ZoneOffset.UTC), v.getObject(row));
        }
        assertThrows(QuackUnsupportedTypeException.class, () -> decodeSequence(type, Long.MAX_VALUE - 1, 1, 2));
        assertThrows(QuackProtocolException.class, () -> decodeSequence(type, Long.MAX_VALUE - 1, 3, 2));
        assertThrows(QuackProtocolException.class, () -> decodeSequence(type, Long.MIN_VALUE, -1, 2));
        assertThrows(QuackProtocolException.class,
                () -> decodeSequence(LogicalType.of(LogicalTypeId.DATE), (long) Integer.MAX_VALUE + 1, 0, 1));
        assertThrows(QuackUnsupportedTypeException.class,
                () -> decodeSequence(LogicalType.of(LogicalTypeId.TIME), 86_400_000_000L - 1, 1, 2));
    }

    @Test
    void dateEncodingChecksFiniteInt32DaysBeforeNarrowing() {
        for (long raw : new long[]{Integer.MIN_VALUE, Integer.MIN_VALUE + 2, 0, Integer.MAX_VALUE - 1}) {
            assertEquals(raw, encodedTicks(LogicalTypeId.DATE, LocalDate.ofEpochDay(raw)));
        }
        for (long raw : new long[]{-Integer.MAX_VALUE, Integer.MAX_VALUE, (long) Integer.MIN_VALUE - 1, (long) Integer.MAX_VALUE + 1}) {
            assertThrows(QuackProtocolException.class, () -> encodedTicks(LogicalTypeId.DATE, LocalDate.ofEpochDay(raw)));
        }
    }

    @Test
    void timestampEncodingPreservesFiniteEndpointsAndQuantization() {
        for (LogicalTypeId id : TIMESTAMPS) {
            long units = units(id);
            long[] ticks = units == 1
                    ? new long[]{LocalDateTime.MIN.toEpochSecond(ZoneOffset.UTC), -1, 0, 1,
                            LocalDateTime.MAX.toEpochSecond(ZoneOffset.UTC)}
                    : new long[]{Long.MIN_VALUE, Long.MIN_VALUE + 2, -1, 0, 1, Long.MAX_VALUE - 1};
            for (long raw : ticks) {
                Instant instant = instant(BigInteger.valueOf(raw), units);
                assertEquals(raw, encodedTicks(id, value(id, instant)), id + " " + raw);
                Instant fraction = instant.plusNanos(1_000_000_000L / units - 1);
                assertEquals(raw, encodedTicks(id, value(id, fraction)), id + " quantization " + raw);
            }
            assertEquals(-1, encodedTicks(id, value(id, Instant.ofEpochSecond(-1, 999_999_999))), id.name());
        }
        assertEquals(Long.MIN_VALUE + 2, encodedTicks(LogicalTypeId.TIMESTAMP_NS,
                LocalDateTime.parse("1677-09-21T00:12:43.145224194")));
    }

    @Test
    void timestampEncodingRejectsOverflowAndInfinityCollisions() {
        for (LogicalTypeId id : TIMESTAMPS) {
            if (id == LogicalTypeId.TIMESTAMP_SEC) continue; // Java's local timestamp range is narrower than int64 seconds.
            long units = units(id);
            for (BigInteger raw : new BigInteger[]{BigInteger.valueOf(-Long.MAX_VALUE), BigInteger.valueOf(Long.MAX_VALUE),
                    BigInteger.valueOf(Long.MIN_VALUE).subtract(BigInteger.ONE),
                    BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE)}) {
                Object value = value(id, instant(raw, units));
                assertThrows(QuackProtocolException.class, () -> encodedTicks(id, value), id + " " + raw);
            }
        }
        assertThrows(QuackProtocolException.class, () -> encodedTicks(LogicalTypeId.TIMESTAMP_NS,
                LocalDateTime.parse("2262-04-11T23:47:16.999999999")));
    }

    private static long units(LogicalTypeId id) {
        return switch (id) {
            case TIMESTAMP_SEC -> 1;
            case TIMESTAMP_MS -> 1_000;
            case TIMESTAMP_NS -> 1_000_000_000;
            default -> 1_000_000;
        };
    }

    private static Instant instant(BigInteger ticks, long units) {
        BigInteger[] parts = ticks.divideAndRemainder(BigInteger.valueOf(units));
        return Instant.ofEpochSecond(parts[0].longValueExact(), parts[1].longValueExact() * (1_000_000_000L / units));
    }

    private static Object value(LogicalTypeId id, Instant instant) {
        return id == LogicalTypeId.TIMESTAMP_TZ ? OffsetDateTime.ofInstant(instant, ZoneOffset.ofHours(3))
                : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static DecodedVector decodeRaw(LogicalTypeId id, long raw, VectorType encoding, boolean valid) {
        LogicalType type = LogicalType.of(id);
        if (encoding == VectorType.SEQUENCE) return decodeSequence(type, raw, 0, 3);
        int count = encoding == VectorType.FLAT ? 2 : 3;
        int rows = encoding == VectorType.CONSTANT ? 1 : 2;
        int width = id == LogicalTypeId.DATE ? 4 : 8;
        ByteBuffer bytes = ByteBuffer.allocate(rows * width).order(ByteOrder.LITTLE_ENDIAN);
        if (width == 4) bytes.putInt((int) raw); else bytes.putLong(raw);
        BinaryWriter writer = new BinaryWriter();
        writer.writeObject(obj -> {
            if (encoding != VectorType.FLAT) obj.writeField(90, () -> obj.writeUleb(encoding.wireId()));
            if (encoding == VectorType.DICTIONARY) {
                obj.writeField(91, () -> obj.writeBlob(new byte[]{0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0}));
                obj.writeField(92, () -> obj.writeUleb(rows));
            }
            obj.writeField(100, () -> obj.writeBool(!valid));
            if (!valid) obj.writeField(101, () -> obj.writeBlob(new byte[]{(byte) (rows == 1 ? 0 : 2), 0, 0, 0, 0, 0, 0, 0}));
            obj.writeField(102, () -> obj.writeBlob(bytes.array()));
        });
        BinaryReader reader = new BinaryReader(writer.toByteArray());
        DecodedVector result = VectorCodec.decodeVector(reader, type, count);
        reader.assertEof();
        return result;
    }

    private static DecodedVector decodeSequence(LogicalType type, long start, long increment, int count) {
        BinaryWriter writer = new BinaryWriter();
        writer.writeObject(obj -> {
            obj.writeField(90, () -> obj.writeUleb(VectorType.SEQUENCE.wireId()));
            obj.writeField(91, () -> obj.writeSleb(start));
            obj.writeField(92, () -> obj.writeSleb(increment));
        });
        BinaryReader reader = new BinaryReader(writer.toByteArray());
        DecodedVector result = VectorCodec.decodeVector(reader, type, count);
        reader.assertEof();
        return result;
    }

    private static long encodedTicks(LogicalTypeId id, Object value) {
        LogicalType type = LogicalType.of(id);
        BinaryWriter writer = new BinaryWriter();
        VectorCodec.encodeVector(writer, type, new DecodedVector.ObjectVec(type, new Object[]{value}));
        BinaryReader reader = new BinaryReader(writer.toByteArray());
        assertFalse(reader.readRequiredField(100, reader::readBool));
        byte[] bytes = reader.readRequiredField(102, reader::readBlob);
        reader.readEndObject();
        reader.assertEof();
        assertEquals(id == LogicalTypeId.DATE ? 4 : 8, bytes.length);
        ByteBuffer raw = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        return id == LogicalTypeId.DATE ? raw.getInt() : raw.getLong();
    }
}

package com.gizmodata.quack.jdbc.message;

import com.gizmodata.quack.jdbc.QuackProtocolException;
import com.gizmodata.quack.jdbc.codec.BinaryReader;
import com.gizmodata.quack.jdbc.codec.BinaryWriter;
import com.gizmodata.quack.jdbc.type.LogicalType;
import com.gizmodata.quack.jdbc.type.LogicalTypeId;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.*;

class TemporalCodecTest {
    private static final LogicalTypeId[] TIMESTAMPS = {LogicalTypeId.TIMESTAMP_SEC, LogicalTypeId.TIMESTAMP_MS,
            LogicalTypeId.TIMESTAMP, LogicalTypeId.TIMESTAMP_NS, LogicalTypeId.TIMESTAMP_TZ};

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

    private static long encodedTicks(LogicalTypeId id, Object value) {
        LogicalType type = LogicalType.of(id);
        BinaryWriter writer = new BinaryWriter();
        VectorCodec.encodeVector(writer, type, new DecodedVector.ObjectVec(type, new Object[]{value}));
        BinaryReader reader = new BinaryReader(writer.toByteArray());
        assertFalse(reader.readRequiredField(100, reader::readBool));
        byte[] bytes = reader.readRequiredField(102, reader::readBlob);
        reader.readEndObject();
        reader.assertEof();
        assertEquals(8, bytes.length);
        return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).getLong();
    }
}

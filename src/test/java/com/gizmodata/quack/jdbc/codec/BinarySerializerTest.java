package com.gizmodata.quack.jdbc.codec;

import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BinarySerializerTest {

    @Test
    void ulebRoundTripSmallValues() {
        for (long v : new long[]{0, 1, 127, 128, 255, 256, 16383, 16384, 1_000_000L, Long.MAX_VALUE}) {
            BinaryWriter w = new BinaryWriter();
            w.writeUleb(v);
            BinaryReader r = new BinaryReader(w.toByteArray());
            assertEquals(v, r.readUlebLong(), "uleb round-trip failed for " + v);
            assertTrue(r.eof());
        }
    }

    @Test
    void ulebHandlesUnsignedSentinel() {
        BinaryWriter w = new BinaryWriter();
        w.writeUleb(-1L);                    // OPTIONAL_INDEX_INVALID = 0xFFFF_FFFF_FFFF_FFFF
        BinaryReader r = new BinaryReader(w.toByteArray());
        long read = r.readUlebLong();
        assertEquals(-1L, read);             // bits preserved
        assertEquals(BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE),
                BigInteger.valueOf(read).add(BigInteger.ONE.shiftLeft(64)));
    }

    @Test
    void slebRoundTrip() {
        for (long v : new long[]{0, 1, -1, 63, -64, 64, -65, Long.MAX_VALUE, Long.MIN_VALUE}) {
            BinaryWriter w = new BinaryWriter();
            w.writeSleb(v);
            BinaryReader r = new BinaryReader(w.toByteArray());
            assertEquals(v, r.readSlebLong(), "sleb round-trip failed for " + v);
        }
    }

    @Test
    void fixedIntsAreLittleEndian() {
        BinaryWriter w = new BinaryWriter();
        w.writeFixedInt32(0x01020304);
        assertArrayEquals(new byte[]{0x04, 0x03, 0x02, 0x01}, w.toByteArray());
        BinaryReader r = new BinaryReader(w.toByteArray());
        assertEquals(0x01020304, r.readFixedInt32());
    }

    @Test
    void stringRoundTrip() {
        BinaryWriter w = new BinaryWriter();
        w.writeString("héllo 🦆");
        BinaryReader r = new BinaryReader(w.toByteArray());
        assertEquals("héllo 🦆", r.readString());
    }

    @Test
    void objectsTerminateWithFieldEnd() {
        BinaryWriter w = new BinaryWriter();
        w.writeObject(obj -> {
            obj.writeField(1, () -> obj.writeUleb(42));
            obj.writeField(2, () -> obj.writeString("x"));
        });
        BinaryReader r = new BinaryReader(w.toByteArray());
        r.readObject(() -> {
            assertEquals(42, r.readRequiredField(1, r::readUlebInt));
            assertEquals("x", r.readRequiredField(2, r::readString));
            return null;
        });
        assertTrue(r.eof());
    }

    @Test
    void optionalFieldsSkipMissing() {
        BinaryWriter w = new BinaryWriter();
        w.writeObject(obj -> obj.writeField(3, () -> obj.writeUleb(7)));
        BinaryReader r = new BinaryReader(w.toByteArray());
        r.readObject(() -> {
            assertEquals("default", r.readOptionalField(1, r::readString, "default"));
            assertEquals(7, r.readRequiredField(3, r::readUlebInt));
            return null;
        });
    }

    @Test
    void hugeIntRoundTrip() {
        BigInteger value = BigInteger.valueOf(Long.MAX_VALUE).multiply(BigInteger.valueOf(3));
        HugeIntParts parts = HugeIntParts.ofSigned(value);
        BinaryWriter w = new BinaryWriter();
        w.writeHugeInt(parts);
        BinaryReader r = new BinaryReader(w.toByteArray());
        HugeIntParts read = r.readHugeInt();
        assertEquals(value, read.toSignedBigInteger());
    }
}

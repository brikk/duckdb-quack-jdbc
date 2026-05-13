package com.gizmodata.quack.jdbc.codec;

import com.gizmodata.quack.jdbc.QuackProtocolException;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Writes DuckDB BinarySerializer-compatible primitives. Little-endian
 * uint16 field IDs, LEB128/SLEB128 variable-length integers, fixed-width
 * native primitives, and length-prefixed strings, blobs, and lists. Objects
 * are terminated with a FIELD_END (0xFFFF) field id.
 */
public final class BinaryWriter {

    private byte[] buffer;
    private int offset;

    public BinaryWriter() {
        this(1024);
    }

    public BinaryWriter(int initialCapacity) {
        this.buffer = new byte[Math.max(16, initialCapacity)];
        this.offset = 0;
    }

    public int size() {
        return offset;
    }

    public byte[] toByteArray() {
        byte[] out = new byte[offset];
        System.arraycopy(buffer, 0, out, 0, offset);
        return out;
    }

    public void writeObject(Consumer<BinaryWriter> body) {
        body.accept(this);
        writeFieldId(QuackConstants.FIELD_END);
    }

    public void writeField(int fieldId, Runnable body) {
        writeFieldId(fieldId);
        body.run();
    }

    public void writeFieldId(int fieldId) {
        if (fieldId < 0 || fieldId > 0xFFFF) {
            throw new QuackProtocolException("Invalid field id " + fieldId);
        }
        ensure(2);
        buffer[offset++] = (byte) (fieldId & 0xFF);
        buffer[offset++] = (byte) ((fieldId >>> 8) & 0xFF);
    }

    public void writeByte(int value) {
        if (value < 0 || value > 0xFF) {
            throw new QuackProtocolException("Invalid byte " + value);
        }
        ensure(1);
        buffer[offset++] = (byte) value;
    }

    public void writeBytes(byte[] value) {
        ensure(value.length);
        System.arraycopy(value, 0, buffer, offset, value.length);
        offset += value.length;
    }

    public void writeBool(boolean value) {
        writeByte(value ? 1 : 0);
    }

    /** Write an unsigned LEB128 integer. {@code value} is interpreted as unsigned 64-bit. */
    public void writeUleb(long value) {
        long current = value;
        while ((current & ~0x7FL) != 0) {
            writeByte((int) ((current & 0x7FL) | 0x80L));
            current >>>= 7;
        }
        writeByte((int) (current & 0x7FL));
    }

    /** Write a signed LEB128 integer. */
    public void writeSleb(long value) {
        long current = value;
        boolean more = true;
        while (more) {
            int b = (int) (current & 0x7FL);
            current >>= 7;
            boolean signBitSet = (b & 0x40) != 0;
            if ((current == 0 && !signBitSet) || (current == -1 && signBitSet)) {
                more = false;
            } else {
                b |= 0x80;
            }
            writeByte(b);
        }
    }

    public void writeString(String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeStringBytes(bytes);
    }

    public void writeStringBytes(byte[] bytes) {
        writeUleb(bytes.length);
        writeBytes(bytes);
    }

    public void writeBlob(byte[] bytes) {
        writeUleb(bytes.length);
        writeBytes(bytes);
    }

    public <T> void writeList(List<? extends T> items, ListElementWriter<T> writer) {
        writeUleb(items.size());
        for (int i = 0; i < items.size(); i++) {
            writer.write(items.get(i), i);
        }
    }

    public <T> void writeNullable(T value, Consumer<T> writeValue) {
        if (value == null) {
            writeBool(false);
            return;
        }
        writeBool(true);
        writeValue.accept(value);
    }

    public void writeHugeInt(HugeIntParts parts) {
        writeSleb(parts.upper());
        writeUleb(parts.lower());
    }

    public void writeFixedInt8(int value) {
        writeByte(value & 0xFF);
    }

    public void writeFixedUint8(int value) {
        writeByte(value & 0xFF);
    }

    public void writeFixedInt16(int value) {
        ensure(2);
        buffer[offset++] = (byte) (value & 0xFF);
        buffer[offset++] = (byte) ((value >>> 8) & 0xFF);
    }

    public void writeFixedUint16(int value) {
        writeFixedInt16(value);
    }

    public void writeFixedInt32(int value) {
        ensure(4);
        buffer[offset++] = (byte) (value & 0xFF);
        buffer[offset++] = (byte) ((value >>> 8) & 0xFF);
        buffer[offset++] = (byte) ((value >>> 16) & 0xFF);
        buffer[offset++] = (byte) ((value >>> 24) & 0xFF);
    }

    public void writeFixedUint32(long value) {
        writeFixedInt32((int) value);
    }

    public void writeFixedFloat32(float value) {
        writeFixedInt32(Float.floatToRawIntBits(value));
    }

    public void writeFixedFloat64(double value) {
        writeFixedInt64(Double.doubleToRawLongBits(value));
    }

    public void writeFixedInt64(long value) {
        ensure(8);
        buffer[offset++] = (byte) (value & 0xFFL);
        buffer[offset++] = (byte) ((value >>> 8) & 0xFFL);
        buffer[offset++] = (byte) ((value >>> 16) & 0xFFL);
        buffer[offset++] = (byte) ((value >>> 24) & 0xFFL);
        buffer[offset++] = (byte) ((value >>> 32) & 0xFFL);
        buffer[offset++] = (byte) ((value >>> 40) & 0xFFL);
        buffer[offset++] = (byte) ((value >>> 48) & 0xFFL);
        buffer[offset++] = (byte) ((value >>> 56) & 0xFFL);
    }

    public void writeFixedUint64(long value) {
        writeFixedInt64(value);
    }

    private void ensure(int needed) {
        if (offset + needed <= buffer.length) {
            return;
        }
        int newSize = buffer.length;
        while (newSize < offset + needed) {
            newSize <<= 1;
        }
        byte[] next = new byte[newSize];
        System.arraycopy(buffer, 0, next, 0, offset);
        buffer = next;
    }

    @FunctionalInterface
    public interface ListElementWriter<T> {
        void write(T item, int index);
    }
}

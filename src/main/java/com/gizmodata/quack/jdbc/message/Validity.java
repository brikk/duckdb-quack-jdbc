package com.gizmodata.quack.jdbc.message;

/**
 * Helpers for bit-packed validity bitmaps. One bit per row, with bit
 * value 1 meaning "valid" and 0 meaning "null", packed into a
 * {@code long[]} so the per-row overhead is 1 bit (matching the wire
 * format) instead of the 1 byte/row of a {@code boolean[]}.
 *
 * <p>Bit ordering matches the Quack wire format: row {@code N}'s bit
 * lives at position {@code N % 64} of {@code validity[N / 64]}. The
 * little-endian byte pack-in mirrors the byte-aligned bitmap the
 * server sends.
 *
 * <p>A {@code null} validity array means "every row is valid" — this is
 * the common case for non-nullable columns and avoids an allocation.
 */
public final class Validity {

    private Validity() {
    }

    /** Number of {@code long}s required to hold validity for {@code count} rows. */
    public static int wordCount(int count) {
        if (count < 0) throw new IllegalArgumentException("Negative validity row count");
        return (int) (((long) count + 63) >>> 6);
    }

    /** Number of bytes the wire format uses for the validity mask. */
    public static int wireByteCount(int count) {
        return wordCount(count) * 8;
    }

    public static boolean isValid(long[] validity, int row) {
        if (validity == null) return true;
        return (validity[row >>> 6] & (1L << (row & 63))) != 0L;
    }

    public static boolean isNull(long[] validity, int row) {
        return !isValid(validity, row);
    }

    /** Decode the wire-format validity bytes into a {@code long[]} bitmap. */
    public static long[] fromBytes(byte[] bytes, int count) {
        long[] out = new long[wordCount(count)];
        for (int i = 0; i < out.length; i++) {
            long v = 0L;
            int base = i * 8;
            for (int b = 0; b < 8 && base + b < bytes.length; b++) {
                v |= ((long) (bytes[base + b] & 0xFF)) << (b * 8);
            }
            out[i] = v;
        }
        return out;
    }

    /** Encode a {@code long[]} bitmap into wire-format validity bytes. */
    public static byte[] toBytes(long[] validity, int count) {
        byte[] out = new byte[wireByteCount(count)];
        if (validity == null) {
            // All-valid: every byte is 0xFF except trailing bits beyond count
            for (int i = 0; i < count; i++) {
                out[i >>> 3] |= (byte) (1 << (i & 7));
            }
            return out;
        }
        for (int i = 0; i < validity.length; i++) {
            long v = validity[i];
            for (int b = 0; b < 8; b++) {
                out[i * 8 + b] = (byte) ((v >>> (b * 8)) & 0xFF);
            }
        }
        return out;
    }

    /** Allocate a validity bitmap that marks every row as valid (all 1s up to count). */
    public static long[] allValid(int count) {
        long[] out = new long[wordCount(count)];
        int fullWords = count >>> 6;
        for (int i = 0; i < fullWords; i++) out[i] = -1L;
        int remaining = count & 63;
        if (remaining > 0 && fullWords < out.length) {
            out[fullWords] = (1L << remaining) - 1L;
        }
        return out;
    }

    public static void setValid(long[] validity, int row) {
        validity[row >>> 6] |= (1L << (row & 63));
    }

    public static void setNull(long[] validity, int row) {
        validity[row >>> 6] &= ~(1L << (row & 63));
    }
}

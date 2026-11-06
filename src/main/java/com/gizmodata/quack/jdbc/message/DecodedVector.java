package com.gizmodata.quack.jdbc.message;

import com.gizmodata.quack.jdbc.type.LogicalType;
import com.gizmodata.quack.jdbc.type.LogicalTypeId;

import java.util.Objects;

/**
 * Decoded representation of a single column from a {@link DataChunk}.
 *
 * <p>Backed by primitive arrays for fixed-width numeric / boolean
 * physical types to avoid the per-row boxing cost of a generic
 * {@code List<Object>}. Logical types whose materialized Java
 * representation is not a primitive (DATE → {@code LocalDate},
 * TIMESTAMP → {@code LocalDateTime}, DECIMAL → {@code BigDecimal},
 * VARCHAR / BLOB / nested types, etc.) fall through to
 * {@link ObjectVec}. INTEGER/BIGINT sequences and primitive constants keep
 * compact representations instead of allocating an array per logical row.
 *
 * <p>Null handling: {@code validity} is a bit-packed {@code long[]}
 * with row {@code N}'s bit at position {@code N % 64} of
 * {@code validity[N / 64]}. A {@code null} validity means every row is
 * valid (cheaper for the common non-nullable case). For {@link ObjectVec}
 * a null value in the array is the null signal.
 */
public sealed interface DecodedVector {

    LogicalType type();

    int size();

    boolean isNull(int row);

    /** Materialize the row as a boxed Java value (or {@code null}). */
    Object getObject(int row);

    default boolean getBoolean(int row) {
        Object v = getObject(row);
        return v != null && (v instanceof Boolean b ? b : ((Number) v).longValue() != 0L);
    }

    default byte getByte(int row) {
        Object v = getObject(row);
        return v == null ? 0 : ((Number) v).byteValue();
    }

    default short getShort(int row) {
        Object v = getObject(row);
        return v == null ? 0 : ((Number) v).shortValue();
    }

    default int getInt(int row) {
        Object v = getObject(row);
        return v == null ? 0 : ((Number) v).intValue();
    }

    default long getLong(int row) {
        Object v = getObject(row);
        return v == null ? 0L : ((Number) v).longValue();
    }

    default float getFloat(int row) {
        Object v = getObject(row);
        return v == null ? 0f : ((Number) v).floatValue();
    }

    default double getDouble(int row) {
        Object v = getObject(row);
        return v == null ? 0d : ((Number) v).doubleValue();
    }

    /** INTEGER arithmetic progression, preserving the existing int narrowing semantics. */
    record IntSequenceVec(LogicalType type, int size, long start, long increment) implements DecodedVector {
        public IntSequenceVec {
            if (type.id() != LogicalTypeId.INTEGER || size < 0) {
                throw new IllegalArgumentException("Invalid INTEGER sequence type or size");
            }
        }
        @Override public boolean isNull(int row) { Objects.checkIndex(row, size); return false; }
        @Override public Object getObject(int row) { return Integer.valueOf(getInt(row)); }
        @Override public int getInt(int row) {
            Objects.checkIndex(row, size);
            return (int) (start + increment * row);
        }
        @Override public long getLong(int row) { return getInt(row); }
        @Override public double getDouble(int row) { return getInt(row); }
    }

    /** BIGINT arithmetic progression; multiplication/addition retain two's-complement wrapping. */
    record LongSequenceVec(LogicalType type, int size, long start, long increment) implements DecodedVector {
        public LongSequenceVec {
            if (type.id() != LogicalTypeId.BIGINT || size < 0) {
                throw new IllegalArgumentException("Invalid BIGINT sequence type or size");
            }
        }
        @Override public boolean isNull(int row) { Objects.checkIndex(row, size); return false; }
        @Override public Object getObject(int row) { return Long.valueOf(getLong(row)); }
        @Override public long getLong(int row) {
            Objects.checkIndex(row, size);
            return start + increment * row;
        }
        @Override public double getDouble(int row) { return getLong(row); }
    }

    /** One immutable primitive wrapper (or null), without an expanded array or validity mask. */
    record ConstantVec(LogicalType type, int size, Object value) implements DecodedVector {
        public ConstantVec {
            Class<?> expected = valueClass(type);
            if (size < 0 || expected == null || value != null && !expected.isInstance(value)) {
                throw new IllegalArgumentException("Invalid primitive constant type, size or value");
            }
        }
        @Override public boolean isNull(int row) { Objects.checkIndex(row, size); return value == null; }
        @Override public Object getObject(int row) { Objects.checkIndex(row, size); return value; }

        // Only types whose flat vector is primitive-backed; the box must match
        // what that flat vector's getObject returns.
        static Class<?> valueClass(LogicalType type) {
            return switch (type.id()) {
                case BOOLEAN -> Boolean.class;
                case TINYINT -> Byte.class;
                case SMALLINT, UTINYINT -> Short.class;
                case INTEGER, USMALLINT -> Integer.class;
                case BIGINT, UINTEGER -> Long.class;
                case FLOAT -> Float.class;
                case DOUBLE -> Double.class;
                default -> null;
            };
        }
    }

    record BoolVec(LogicalType type, boolean[] values, long[] validity) implements DecodedVector {
        @Override public int size() { return values.length; }
        @Override public boolean isNull(int row) { return Validity.isNull(validity, row); }
        @Override public Object getObject(int row) { return isNull(row) ? null : Boolean.valueOf(values[row]); }
        @Override public boolean getBoolean(int row) { return values[row]; }
    }

    record ByteVec(LogicalType type, byte[] values, long[] validity) implements DecodedVector {
        @Override public int size() { return values.length; }
        @Override public boolean isNull(int row) { return Validity.isNull(validity, row); }
        @Override public Object getObject(int row) { return isNull(row) ? null : Byte.valueOf(values[row]); }
        @Override public byte getByte(int row) { return values[row]; }
        @Override public short getShort(int row) { return values[row]; }
        @Override public int getInt(int row) { return values[row]; }
        @Override public long getLong(int row) { return values[row]; }
        @Override public double getDouble(int row) { return values[row]; }
    }

    record ShortVec(LogicalType type, short[] values, long[] validity) implements DecodedVector {
        @Override public int size() { return values.length; }
        @Override public boolean isNull(int row) { return Validity.isNull(validity, row); }
        @Override public Object getObject(int row) { return isNull(row) ? null : Short.valueOf(values[row]); }
        @Override public short getShort(int row) { return values[row]; }
        @Override public int getInt(int row) { return values[row]; }
        @Override public long getLong(int row) { return values[row]; }
        @Override public double getDouble(int row) { return values[row]; }
    }

    record IntVec(LogicalType type, int[] values, long[] validity) implements DecodedVector {
        @Override public int size() { return values.length; }
        @Override public boolean isNull(int row) { return Validity.isNull(validity, row); }
        @Override public Object getObject(int row) { return isNull(row) ? null : Integer.valueOf(values[row]); }
        @Override public int getInt(int row) { return values[row]; }
        @Override public long getLong(int row) { return values[row]; }
        @Override public double getDouble(int row) { return values[row]; }
    }

    record LongVec(LogicalType type, long[] values, long[] validity) implements DecodedVector {
        @Override public int size() { return values.length; }
        @Override public boolean isNull(int row) { return Validity.isNull(validity, row); }
        @Override public Object getObject(int row) { return isNull(row) ? null : Long.valueOf(values[row]); }
        @Override public long getLong(int row) { return values[row]; }
        @Override public double getDouble(int row) { return values[row]; }
    }

    record FloatVec(LogicalType type, float[] values, long[] validity) implements DecodedVector {
        @Override public int size() { return values.length; }
        @Override public boolean isNull(int row) { return Validity.isNull(validity, row); }
        @Override public Object getObject(int row) { return isNull(row) ? null : Float.valueOf(values[row]); }
        @Override public float getFloat(int row) { return values[row]; }
        @Override public double getDouble(int row) { return values[row]; }
    }

    record DoubleVec(LogicalType type, double[] values, long[] validity) implements DecodedVector {
        @Override public int size() { return values.length; }
        @Override public boolean isNull(int row) { return Validity.isNull(validity, row); }
        @Override public Object getObject(int row) { return isNull(row) ? null : Double.valueOf(values[row]); }
        @Override public double getDouble(int row) { return values[row]; }
        @Override public float getFloat(int row) { return (float) values[row]; }
    }

    /** Fallback for any logical type whose materialized form is not a primitive. */
    record ObjectVec(LogicalType type, Object[] values) implements DecodedVector {
        @Override public int size() { return values.length; }
        @Override public boolean isNull(int row) { return values[row] == null; }
        @Override public Object getObject(int row) { return values[row]; }
    }
}

package com.gizmodata.quack.jdbc.sql;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.lang.reflect.Proxy;
import java.sql.Blob;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

class QuackRangeValidationTest {
    private static final long[] INDICES = {-1, 0, 1, 3, 4, 5, 4294967297L, Long.MAX_VALUE, Long.MIN_VALUE};

    static Stream<Arguments> slices() {
        return Arrays.stream(INDICES).boxed().flatMap(index ->
                Stream.of(-1, 0, 1, 2, Integer.MAX_VALUE).map(count -> Arguments.of(index, count)));
    }

    @ParameterizedTest
    @MethodSource("slices")
    void arraySlicesValidateBeforeNarrowingAndTreatCountAsMaximum(long index, int count) throws Exception {
        List<Integer> values = List.of(10, 20, 30);
        var array = new QuackArray(values, null);
        if (index < 1 || index > 4 || count < 0) {
            assertThrows(SQLException.class, () -> array.getArray(index, count));
            assertThrows(SQLException.class, () -> array.getArray(index, count, Map.of()));
        } else {
            int start = (int) index - 1;
            Object[] expected = values.subList(start, start + Math.min(count, 3 - start)).toArray();
            assertArrayEquals(expected, (Object[]) array.getArray(index, count));
            assertArrayEquals(expected, (Object[]) array.getArray(index, count, Map.of()));
        }
    }

    @ParameterizedTest
    @MethodSource("slices")
    void blobBytesValidateBeforeNarrowingAndTreatLengthAsMaximum(long pos, int length) throws Exception {
        byte[] bytes = {10, 20, 30};
        var blob = new QuackBlob(bytes);
        if (pos < 1 || length < 0) {
            assertThrows(SQLException.class, () -> blob.getBytes(pos, length));
        } else {
            int start = (int) Math.min(pos - 1, bytes.length);
            byte[] expected = Arrays.copyOfRange(bytes, start, start + Math.min(length, bytes.length - start));
            assertArrayEquals(expected, blob.getBytes(pos, length));
        }
    }

    static Stream<Arguments> blobRanges() {
        return Arrays.stream(INDICES).boxed().flatMap(pos ->
                Stream.of(-1L, -4294967296L, 0L, 1L, 3L, (long) Integer.MAX_VALUE,
                        4294967297L, Long.MAX_VALUE).map(length -> Arguments.of(pos, length)));
    }

    @ParameterizedTest
    @MethodSource("blobRanges")
    void blobStreamsRequireAnExactRange(long pos, long length) throws Exception {
        byte[] bytes = {10, 20, 30};
        var blob = new QuackBlob(bytes);
        if (pos < 1 || pos > 3 || length < 0 || length > 3 - (pos - 1)) {
            assertThrows(SQLException.class, () -> blob.getBinaryStream(pos, length));
        } else {
            try (InputStream stream = blob.getBinaryStream(pos, length)) {
                assertArrayEquals(Arrays.copyOfRange(bytes, (int) pos - 1, (int) (pos - 1 + length)),
                        stream.readAllBytes());
            }
        }
    }

    @Test
    void emptyValuesAndNullElementsKeepTheirExistingMeaning() throws Exception {
        var array = new QuackArray(List.of(), null);
        assertArrayEquals(new Object[0], (Object[]) array.getArray(1, Integer.MAX_VALUE));
        assertThrows(SQLException.class, () -> array.getArray(2, 0));
        assertArrayEquals(new Object[]{null, 2},
                (Object[]) new QuackArray(Arrays.asList(null, 2), null).getArray(1, 2));
        for (byte[] bytes : new byte[][]{null, new byte[0]}) {
            var blob = new QuackBlob(bytes);
            assertEquals(0, blob.length());
            assertArrayEquals(new byte[0], blob.getBytes(1, Integer.MAX_VALUE));
            assertArrayEquals(new byte[0], blob.getBinaryStream().readAllBytes());
            assertThrows(SQLException.class, () -> blob.getBinaryStream(1, 0));
            assertEquals(-1, blob.position(new byte[]{10}, 1));
        }
    }

    @Test
    void blobSearchChecksLongStartBeforeAccessingEitherPatternForm() throws Exception {
        var blob = new QuackBlob(new byte[]{10, 20, 30});
        for (long start : INDICES) {
            for (byte[] bytes : new byte[][]{null, new byte[0], {30}}) {
                List<String> calls = new ArrayList<>();
                Blob pattern = bytes == null ? null : foreignBlob(bytes.length, bytes, calls);
                if (start < 1) {
                    assertThrows(SQLException.class, () -> blob.position(bytes, start));
                    assertThrows(SQLException.class, () -> blob.position(pattern, start));
                    assertTrue(calls.isEmpty());
                } else {
                    long expected = bytes != null && bytes.length > 0 && start <= 3 ? 3 : -1;
                    assertEquals(expected, blob.position(bytes, start));
                    assertEquals(expected, blob.position(pattern, start));
                    if (start > 3) assertTrue(calls.isEmpty(), "out-of-range search accessed foreign Blob");
                }
            }
        }
        var overlapping = new QuackBlob(new byte[]{1, 1, 1, 2});
        assertEquals(2, overlapping.position(new byte[]{1, 1, 2}, 1));
        assertEquals(2, overlapping.position(new QuackBlob(new byte[]{1, 1, 2}), 1));
    }

    @Test
    void foreignPatternLengthsCannotWrapOrTriggerUnnecessaryReads() throws Exception {
        var blob = new QuackBlob(new byte[]{10, 20, 30});
        for (long length : new long[]{-1, -4294967296L, Long.MIN_VALUE, 0, 2, 4,
                Integer.MAX_VALUE, 4294967297L, Long.MAX_VALUE}) {
            List<String> calls = new ArrayList<>();
            Blob pattern = foreignBlob(length, null, calls);
            if (length < 0) assertThrows(SQLException.class, () -> blob.position(pattern, 3));
            else assertEquals(-1, blob.position(pattern, 3));
            assertEquals(List.of("length"), calls, "must not read a pattern that cannot match");
        }
        List<String> calls = new ArrayList<>();
        assertEquals(2, blob.position(foreignBlob(2, new byte[]{20, 30}, calls), 1));
        assertEquals(List.of("length", "getBytes"), calls);
        assertThrows(SQLException.class,
                () -> blob.position(foreignBlob(2, new byte[]{20}, new ArrayList<>()), 1));
    }

    @Test
    void freedObjectsFailAtEntryIncludingMetadataAndUnsupportedMethods() throws Exception {
        var blob = new QuackBlob(new byte[]{10, 20, 30});
        List<String> calls = new ArrayList<>();
        Blob pattern = foreignBlob(1, new byte[]{10}, calls);
        blob.free();
        blob.free();
        List<Executable> blobMethods = List.of(blob::length, () -> blob.getBytes(1, 0),
                blob::getBinaryStream, () -> blob.getBinaryStream(1, 0),
                () -> blob.position((byte[]) null, 0), () -> blob.position(pattern, 1),
                () -> blob.position((Blob) null, 1), () -> blob.setBytes(1, null),
                () -> blob.setBytes(1, null, 0, 0), () -> blob.setBinaryStream(1), () -> blob.truncate(0));
        for (Executable method : blobMethods) {
            assertTrue(assertThrows(SQLException.class, method).getMessage().contains("free()"));
        }
        assertTrue(calls.isEmpty());

        var array = new QuackArray(List.of(1), null);
        array.free();
        array.free();
        List<Executable> arrayMethods = List.of(array::getBaseType, array::getBaseTypeName,
                array::getArray, () -> array.getArray(Map.of()), () -> array.getArray(1, 0),
                () -> array.getArray(1, 0, Map.of()), array::getResultSet,
                () -> array.getResultSet(Map.of()), () -> array.getResultSet(1, 0),
                () -> array.getResultSet(1, 0, Map.of()));
        for (Executable method : arrayMethods) {
            assertTrue(assertThrows(SQLException.class, method).getMessage().contains("free()"));
        }
    }

    @Test
    void truncateStillValidatesInLongDomainWithoutAddingBlobWrites() throws Exception {
        var blob = new QuackBlob(new byte[]{10, 20, 30});
        for (long length : new long[]{-1, -4294967296L, 4, 4294967297L, Long.MAX_VALUE}) {
            assertThrows(SQLException.class, () -> blob.truncate(length));
            assertEquals(3, blob.length());
        }
        blob.truncate(3);
        blob.truncate(1);
        assertArrayEquals(new byte[]{10}, blob.getBytes(1, 3));
        blob.truncate(0);
        assertEquals(0, blob.length());
        assertThrows(SQLFeatureNotSupportedException.class, () -> blob.setBytes(1, new byte[]{1}));
        assertThrows(SQLFeatureNotSupportedException.class, () -> blob.setBinaryStream(1));
    }

    @ParameterizedTest
    @EnumSource(value = Setter.class, names = {"ASCII_INT", "ASCII_LONG", "BINARY_INT", "BINARY_LONG", "CHAR_INT", "CHAR_LONG"})
    void exactSettersReadOnlyRequestedDataIncludingPartialReads(Setter setter) throws Exception {
        for (int chunk : new int[]{1, 3, 1024}) {
            try (var p = new CapturingStatement()) {
                var input = new TrackedInput("abcd", chunk);
                setter.set(p, 1, input, 2);
                assertEquals(2, input.position, "setter consumed the tail");
                assertFalse(input.closed);
                p.assertLiteral(setter.binary() ? "'\\x61\\x62'::BLOB" : "'ab'");
                assertEquals('c', setter.character() ? input.reader.read() : input.stream.read());
                assertFalse(input.closed);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(value = Setter.class, names = {"ASCII_INT", "ASCII_LONG", "BINARY_INT", "BINARY_LONG", "CHAR_INT", "CHAR_LONG"})
    void zeroAndInvalidLengthsDoNotReadOrCloseAndFailuresPreserveBinding(Setter setter) throws Exception {
        try (var p = new CapturingStatement()) {
            var input = new TrackedInput("abcd", 1);
            input.failure = new IOException("must not read");
            setter.set(p, 1, input, 0);
            p.assertLiteral(setter.binary() ? "''::BLOB" : "''");
            p.setString(1, "previous");
            long[] invalid = setter.name().endsWith("INT") ? new long[]{-1, Integer.MIN_VALUE}
                    : new long[]{-1, -4294967296L, Long.MIN_VALUE, 2147483648L, 4294967297L, Long.MAX_VALUE};
            for (long length : invalid) {
                Class<? extends SQLException> type = length < 0 ? SQLException.class : SQLFeatureNotSupportedException.class;
                assertThrows(type, () -> setter.set(p, 1, input, length));
                assertThrows(type, () -> setter.set(p, 1, null, length));
                p.assertLiteral("'previous'");
            }
            assertEquals(0, input.readCalls);
            assertFalse(input.closed);
        }
    }

    @ParameterizedTest
    @EnumSource(value = Setter.class, names = {"ASCII_INT", "ASCII_LONG", "BINARY_INT", "BINARY_LONG", "CHAR_INT", "CHAR_LONG"})
    void prematureEofIsSqlExceptionAndHugeDeclaredLengthsDoNotPreallocate(Setter setter) throws Exception {
        for (long length : new long[]{5, Integer.MAX_VALUE}) {
            try (var p = new CapturingStatement()) {
                p.setString(1, "previous");
                var input = new TrackedInput("abcd", 1);
                SQLException error = assertThrows(SQLException.class, () -> setter.set(p, 1, input, length));
                assertInstanceOf(EOFException.class, error.getCause());
                assertEquals(4, input.position);
                // JDK versions use different readNBytes buffer sizes; none should approach the declared 2 GiB.
                assertTrue(input.maxRequest <= 64 * 1024, "read buffer too large: " + input.maxRequest);
                assertFalse(input.closed);
                p.assertLiteral("'previous'");
            }
        }
    }

    @ParameterizedTest
    @EnumSource(Setter.class)
    void nullsIoFailuresAndIndexChecksStayAtTheSqlBoundary(Setter setter) throws Exception {
        try (var p = new CapturingStatement()) {
            setter.set(p, 1, null, 2);
            p.assertLiteral("NULL");
            for (int failureAt : new int[]{0, 1}) {
                var failing = new TrackedInput("abcd", 1);
                failing.failure = new IOException("read failed");
                failing.failureAt = failureAt;
                SQLException error = assertThrows(SQLException.class, () -> setter.set(p, 1, failing, 2));
                assertSame(failing.failure, error.getCause());
                assertEquals(failureAt, failing.position);
                assertFalse(failing.closed);
                p.assertLiteral("NULL");
            }
            var input = new TrackedInput("abcd", 1);
            input.failure = new IOException("must not read");
            for (int index : new int[]{-1, 0, 2, Integer.MAX_VALUE}) {
                for (long length : new long[]{0, 2, -1, 4294967297L}) {
                    SQLException invalid = assertThrows(SQLException.class, () -> setter.set(p, index, input, length));
                    assertTrue(invalid.getMessage().contains("Parameter index"));
                }
            }
            p.close();
            for (long length : new long[]{0, 2, -1, 4294967297L}) {
                SQLException closed = assertThrows(SQLException.class, () -> setter.set(p, 1, input, length));
                assertTrue(closed.getMessage().contains("closed"));
            }
            assertEquals(0, input.readCalls);
            assertFalse(input.closed);
        }
    }

    @ParameterizedTest
    @EnumSource(value = Setter.class, names = {"ASCII_UNKNOWN", "BINARY_UNKNOWN", "CHAR_UNKNOWN"})
    void unknownLengthsStillReadToEofAcrossPartialReads(Setter setter) throws Exception {
        for (String text : new String[]{"", "abcd"}) {
            try (var p = new CapturingStatement()) {
                var input = new TrackedInput(text, 1);
                setter.set(p, 1, input, 0);
                p.assertLiteral(setter.binary() ? (text.isEmpty() ? "''::BLOB" : "'\\x61\\x62\\x63\\x64'::BLOB")
                        : "'" + text + "'");
                assertEquals(text.length(), input.position);
                assertFalse(input.closed);
            }
        }
    }

    @Test
    void characterLengthsCountCharactersAndBinaryBytesRemainUnchanged() throws Exception {
        try (var p = new CapturingStatement()) {
            var characters = new TrackedInput("\u03b1\u03b2tail", 1);
            p.setCharacterStream(1, characters.reader, 2L);
            p.assertLiteral("'\u03b1\u03b2'");
            assertEquals(2, characters.position);
            var binary = new TrackedInput("\u0000\u00fftail", 1);
            p.setBinaryStream(1, binary.stream, 2L);
            p.assertLiteral("'\\x00\\xFF'::BLOB");
            assertEquals(2, binary.position);
        }
    }

    private static Blob foreignBlob(long length, byte[] bytes, List<String> calls) {
        return (Blob) Proxy.newProxyInstance(Blob.class.getClassLoader(), new Class<?>[]{Blob.class},
                (proxy, method, args) -> {
                    calls.add(method.getName());
                    if (method.getName().equals("length")) return length;
                    if (method.getName().equals("getBytes")) {
                        assertNotNull(bytes, "must not materialize an unmatchable pattern");
                        assertEquals(1L, args[0]);
                        assertEquals((int) length, args[1]);
                        return bytes;
                    }
                    throw new AssertionError("Unexpected foreign Blob access: " + method);
                });
    }

    private enum Setter {
        ASCII_INT, ASCII_LONG, ASCII_UNKNOWN, BINARY_INT, BINARY_LONG, BINARY_UNKNOWN,
        CHAR_INT, CHAR_LONG, CHAR_UNKNOWN;

        boolean binary() { return name().startsWith("BINARY"); }
        boolean character() { return name().startsWith("CHAR"); }

        void set(PreparedStatement p, int index, TrackedInput input, long length) throws SQLException {
            InputStream stream = input == null ? null : input.stream;
            Reader reader = input == null ? null : input.reader;
            switch (this) {
                case ASCII_INT -> p.setAsciiStream(index, stream, (int) length);
                case ASCII_LONG -> p.setAsciiStream(index, stream, length);
                case ASCII_UNKNOWN -> p.setAsciiStream(index, stream);
                case BINARY_INT -> p.setBinaryStream(index, stream, (int) length);
                case BINARY_LONG -> p.setBinaryStream(index, stream, length);
                case BINARY_UNKNOWN -> p.setBinaryStream(index, stream);
                case CHAR_INT -> p.setCharacterStream(index, reader, (int) length);
                case CHAR_LONG -> p.setCharacterStream(index, reader, length);
                case CHAR_UNKNOWN -> p.setCharacterStream(index, reader);
            }
        }
    }

    private static final class TrackedInput {
        final String text;
        final int chunk;
        int position;
        int readCalls;
        int maxRequest;
        boolean closed;
        IOException failure;
        int failureAt;

        TrackedInput(String text, int chunk) {
            this.text = text;
            this.chunk = chunk;
        }

        private int next(int length) throws IOException {
            readCalls++;
            maxRequest = Math.max(maxRequest, length);
            if (failure != null && position >= failureAt) throw failure;
            if (length == 0) return 0;
            return position == text.length() ? -1 : Math.min(length, Math.min(chunk, text.length() - position));
        }

        final InputStream stream = new InputStream() {
            @Override public int read() throws IOException {
                return next(1) < 0 ? -1 : text.charAt(position++) & 0xff;
            }
            @Override public int read(byte[] target, int offset, int length) throws IOException {
                int n = next(length);
                for (int i = 0; i < n; i++) target[offset + i] = (byte) text.charAt(position++);
                return n;
            }
            @Override public void close() { closed = true; }
        };

        final Reader reader = new Reader() {
            @Override public int read(char[] target, int offset, int length) throws IOException {
                int n = next(length);
                for (int i = 0; i < n; i++) target[offset + i] = text.charAt(position++);
                return n;
            }
            @Override public void close() { closed = true; }
        };
    }

    // Exercise normal binding/interpolation without a server or changing protocol behavior.
    private static final class CapturingStatement extends QuackPreparedStatement {
        private String executedSql;

        CapturingStatement() { super(null, "SELECT ?"); }

        @Override public boolean execute(String sql) {
            executedSql = sql;
            return false;
        }

        void assertLiteral(String literal) throws SQLException {
            execute();
            assertEquals("SELECT /**/" + literal + "/**/", executedSql);
        }
    }
}

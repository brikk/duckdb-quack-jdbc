package com.gizmodata.quack.jdbc.message;

import com.gizmodata.quack.jdbc.QuackProtocolException;
import com.gizmodata.quack.jdbc.codec.BinaryReader;
import com.gizmodata.quack.jdbc.codec.DecodeLimits;
import com.gizmodata.quack.jdbc.type.ChildType;
import com.gizmodata.quack.jdbc.type.ExtraTypeInfo;
import com.gizmodata.quack.jdbc.type.LogicalType;
import com.gizmodata.quack.jdbc.type.LogicalTypeCodec;
import com.gizmodata.quack.jdbc.type.LogicalTypeId;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class VectorValidationTest {
    private static final LogicalType INT = LogicalType.of(LogicalTypeId.INTEGER);
    private static final LogicalType VARCHAR = LogicalType.of(LogicalTypeId.VARCHAR);
    private static final LogicalType LIST = LogicalType.of(LogicalTypeId.LIST,
            new ExtraTypeInfo.ListInfo(INT, Optional.empty()));

    @Test
    void hugeCompressedCountsAreRejectedBeforePayloadReadingOrAllocation() {
        for (int encoding : new int[]{2, 3, 4}) {
            byte[] wire = new Wire().field(90).u(encoding).end().bytes();
            for (int count : new int[]{-1, Integer.MAX_VALUE}) {
                QuackProtocolException error = assertThrows(QuackProtocolException.class,
                        () -> decode(wire, INT, count, 4096, 64));
                assertTrue(error.getMessage().contains(count < 0 ? "Negative" : "maxDecodedBytes"));
            }
        }
        byte[] dictionary = new Wire().field(90).u(3).field(91).blob(le32(0))
                .field(92).u(Integer.MAX_VALUE).field(90).u(2).end().bytes();
        assertBudgetFailure(dictionary, INT, 1, 4096);
        // Scalar object materialization must not use the cheaper primitive-row allowance.
        LogicalType date = LogicalType.of(LogicalTypeId.DATE);
        for (byte[] wire : new byte[][]{flat(le32(0, 1), null), sequence()}) {
            assertBudgetFailure(wire, date, 2, 2303); // 128 object + 128 body + 1024*2
            assertEquals(2, decode(wire, date, 2, 4096, 64).size());
        }
    }

    @Test
    void exactBlobAndCollectionCardinalitiesRejectMissingAndExtraValues() {
        LogicalType struct = LogicalType.of(LogicalTypeId.STRUCT,
                new ExtraTypeInfo.StructInfo(List.of(new ChildType("x", INT)), Optional.empty()));
        for (int size : new int[]{0, 1, 3, 7, 9, 12}) {
            byte[] fixed = flat(new byte[size], null);
            assertThrows(QuackProtocolException.class, () -> decode(fixed, INT, 2));
            byte[] validity = new Wire().field(100).u(1).field(101).blob(new byte[size])
                    .field(102).blob(le32(1, 2)).end().bytes();
            assertThrows(QuackProtocolException.class, () -> decode(validity, INT, 2));
            byte[] selection = new Wire().field(90).u(3).field(91).blob(new byte[size])
                    .field(92).u(1).raw(flat(le32(1), null)).bytes();
            assertThrows(QuackProtocolException.class, () -> decode(selection, INT, 2));
        }
        for (int count : new int[]{1, 3}) {
            Wire strings = new Wire().field(100).u(0).field(102).u(count);
            for (int i = 0; i < count; i++) strings.blob(new byte[]{'x'});
            byte[] wire = strings.end().bytes();
            assertThrows(QuackProtocolException.class, () -> decode(wire, VARCHAR, 2));
            byte[] entries = new Wire().field(100).u(0).field(104).u(0)
                    .field(105).u(count).end().bytes();
            assertThrows(QuackProtocolException.class, () -> decode(entries, LIST, 2));
        }
        for (int count : new int[]{0, 2}) {
            byte[] children = new Wire().field(100).u(0).field(103).u(count).end().bytes();
            assertThrows(QuackProtocolException.class, () -> decode(children, struct, 1));
            byte[] chunk = new Wire().field(100).u(1).field(101).u(1).raw(intType())
                    .field(102).u(count).end().bytes();
            assertThrows(QuackProtocolException.class,
                    () -> VectorCodec.decodeDataChunk(new BinaryReader(chunk)));
        }
        for (int index : new int[]{-1, 1, Integer.MAX_VALUE}) {
            byte[] wire = dictionary(new int[]{index}, 1, flat(le32(7), null));
            assertThrows(QuackProtocolException.class, () -> decode(wire, INT, 1));
        }
    }

    @Test
    void listSlicesValidateOffsetAndLengthButIgnoreNullSlotPayload() {
        for (int[] entry : new int[][]{{3, 0}, {1, 2}, {Integer.MAX_VALUE, 1},
                {1, Integer.MAX_VALUE}, {Integer.MAX_VALUE, Integer.MAX_VALUE}}) {
            byte[] wire = list(new int[][]{entry}, new int[]{7, 8}, null);
            QuackProtocolException error = assertThrows(QuackProtocolException.class,
                    () -> decode(wire, LIST, 1));
            assertTrue(error.getMessage().contains("slice"));
            DecodedVector nullSlot = decode(list(new int[][]{entry}, new int[]{7, 8}, 0L), LIST, 1);
            assertNull(nullSlot.getObject(0));
            assertTrue(nullSlot.isNull(0));
        }
        DecodedVector valid = decode(list(new int[][]{{2, 0}, {0, 2}, {1, 1}},
                new int[]{7, 8}, null), LIST, 3);
        assertEquals(List.of(), valid.getObject(0));
        assertEquals(List.of(7, 8), valid.getObject(1));
        assertEquals(List.of(8), valid.getObject(2));
    }

    @Test
    void arrayChildMultiplicationCannotWrapAndSizeMustMatchMetadata() {
        for (int size : new int[]{1 << 30, Integer.MAX_VALUE}) {
            LogicalType array = LogicalType.of(LogicalTypeId.ARRAY,
                    new ExtraTypeInfo.ArrayInfo(INT, size, Optional.empty()));
            byte[] wire = new Wire().field(100).u(0).field(103).u(size).end().bytes();
            QuackProtocolException error = assertThrows(QuackProtocolException.class,
                    () -> decode(wire, array, 2));
            assertTrue(error.getMessage().contains("integer range"), error.getMessage());
        }
        LogicalType array = LogicalType.of(LogicalTypeId.ARRAY,
                new ExtraTypeInfo.ArrayInfo(INT, 2, Optional.empty()));
        byte[] mismatch = new Wire().field(100).u(0).field(103).u(3).end().bytes();
        assertThrows(QuackProtocolException.class, () -> decode(mismatch, array, 1));
        byte[] valid = new Wire().field(100).u(0).field(103).u(2).field(104)
                .raw(flat(le32(7, 8), null)).end().bytes();
        assertEquals(List.of(7, 8), decode(valid, array, 1).getObject(0));
    }

    @Test
    void compressedFixturesPreserveValuesNullsAndEmptyVectors() {
        byte[][] wires = {
                constant(flat(le32(7), null)),
                dictionary(new int[]{1, 0, 1}, 2, flat(le32(7, 9), null)),
                sequence(),
                constant(dictionary(new int[]{1}, 2, flat(le32(7, 9), null))),
                dictionary(new int[]{2, 0, 1}, 3, sequence())
        };
        Object[][] expected = {{7, 7, 7}, {9, 7, 9}, {10, 12, 14}, {9, 9, 9}, {14, 10, 12}};
        for (int i = 0; i < wires.length; i++) {
            DecodedVector vector = decode(wires[i], INT, 3);
            for (int row = 0; row < 3; row++) assertEquals(expected[i][row], vector.getObject(row));
        }
        for (byte[] wire : new byte[][]{constant(flat(le32(99), 0L)),
                dictionary(new int[]{0, 0, 0}, 1, flat(le32(99), 0L))}) {
            DecodedVector vector = decode(wire, INT, 3);
            for (int row = 0; row < 3; row++) {
                assertNull(vector.getObject(row));
                assertTrue(vector.isNull(row));
            }
        }
        for (byte[] wire : new byte[][]{flat(le32(), null), constant(flat(le32(), null)),
                dictionary(new int[0], 0, flat(le32(), null)), sequence()}) {
            assertEquals(0, decode(wire, INT, 0).size());
        }
        byte[] strings = new Wire().field(100).u(1).field(101).blob(le64(1))
                .field(102).u(2).blob(new byte[0]).blob(new byte[]{(byte) 0xff}).end().bytes();
        DecodedVector vector = decode(strings, VARCHAR, 2);
        assertEquals("", vector.getObject(0));
        assertNull(vector.getObject(1));
        assertEquals(0, decode(new Wire().field(100).u(0).field(102).u(0).end().bytes(), VARCHAR, 0).size());
    }

    @Test
    void inlineCompressedNestingHonorsExactDepthAndShallowSiblingsDoNotAccumulate() {
        for (int depth : new int[]{3, 8, 64, 128}) {
            byte[] wire = flat(le32(7), null);
            for (int i = 0; i < depth - 2; i++) wire = constant(wire);
            assertEquals(7, decode(wire, INT, 1, 1_000_000, depth).getObject(0));
            byte[] tooDeep = constant(wire);
            QuackProtocolException error = assertThrows(QuackProtocolException.class,
                    () -> decode(tooDeep, INT, 1, 1_000_000, depth));
            assertTrue(error.getMessage().contains("maxNestingDepth"));
        }
        byte[] one = constant(flat(le32(7), null));
        Wire siblings = new Wire();
        for (int i = 0; i < 100; i++) siblings.raw(one);
        BinaryReader reader = new BinaryReader(siblings.bytes(), new DecodeLimits(100_000, 1_000_000, 3));
        for (int i = 0; i < 100; i++) assertEquals(7, VectorCodec.decodeVector(reader, INT, 1).getObject(0));
        reader.assertEof();
    }

    @Test
    void listAnyAndStructMetadataHaveBoundedActiveDepth() {
        for (int kind : new int[]{4, 10, 5}) {
            byte[] wire = intType();
            for (int i = 0; i < 5; i++) wire = metadata(kind, wire);
            int depth = 1 + 5 * (kind == 5 ? 3 : 2);
            BinaryReader reader = new BinaryReader(wire, new DecodeLimits(100_000, 1_000_000, depth));
            LogicalType decoded = LogicalTypeCodec.decode(reader);
            assertEquals(kind == 4 ? LogicalTypeId.LIST : kind == 10 ? LogicalTypeId.ANY : LogicalTypeId.STRUCT,
                    decoded.id());
            reader.assertEof();
            byte[] fixture = wire;
            QuackProtocolException error = assertThrows(QuackProtocolException.class,
                    () -> LogicalTypeCodec.decode(new BinaryReader(fixture,
                            new DecodeLimits(100_000, 1_000_000, depth - 1))));
            assertTrue(error.getMessage().contains("maxNestingDepth"));
        }
        Wire siblings = new Wire().field(100).u(100).field(101).u(1).field(100).u(5).field(200).u(100);
        for (int i = 0; i < 100; i++) siblings.field(0).blob(new byte[]{'x'}).field(1).raw(intType()).end();
        BinaryReader reader = new BinaryReader(siblings.end().end().bytes(),
                new DecodeLimits(100_000, 1_000_000, 4));
        ExtraTypeInfo.StructInfo info = (ExtraTypeInfo.StructInfo) LogicalTypeCodec.decode(reader).typeInfo().orElseThrow();
        assertEquals(100, info.childTypes().size());
        reader.assertEof();
    }

    @Test
    void decodedBudgetIsSharedAcrossColumnsAndChunksButResetsPerMessage() {
        DecodeLimits limits = new DecodeLimits(100_000, 2500, 64);
        for (int i = 0; i < 3; i++) {
            QuackMessage.FetchResponse response = (QuackMessage.FetchResponse) MessageCodec.decode(fetch(1, 1), limits);
            assertEquals(7, response.results().get(0).columns().get(0).getObject(0));
        }
        for (int[] shape : new int[][]{{1, 4}, {4, 1}}) {
            byte[] wire = fetch(shape[0], shape[1]);
            QuackProtocolException error = assertThrows(QuackProtocolException.class,
                    () -> MessageCodec.decode(wire, limits));
            assertTrue(error.getMessage().contains("maxDecodedBytes"));
            QuackMessage.FetchResponse response = (QuackMessage.FetchResponse) MessageCodec.decode(wire);
            assertEquals(shape[0], response.results().size());
            for (DataChunk chunk : response.results()) {
                assertEquals(shape[1], chunk.columns().size());
                for (DecodedVector column : chunk.columns()) assertEquals(7, column.getObject(0));
            }
        }
    }

    @Test
    void repeatedOverlappingListSlicesAreChargedForEveryMaterializedCopy() {
        int[][] entries = new int[16][2];
        for (int[] entry : entries) entry[1] = 32;
        int[] values = new int[32];
        Arrays.fill(values, 7);
        byte[] wire = list(entries, values, null);
        // Parent 768, entries 2336, child 1280, blob/subreader 208; charge every slice.
        long exact = 4592 + 16L * (64 + 128 * 32);
        assertBudgetFailure(wire, LIST, 16, exact - 1);
        DecodedVector vector = decode(wire, LIST, 16, exact, 64);
        for (int row = 0; row < 16; row++) {
            List<?> slice = (List<?>) vector.getObject(row);
            assertEquals(32, slice.size());
            assertTrue(slice.stream().allMatch(value -> value.equals(7)));
            if (row > 0) assertNotSame(vector.getObject(0), slice);
        }
        assertBudgetFailure(wire, LIST, 16, 16_000);
        assertEquals(16, decode(list(new int[16][2], values, null), LIST, 16, 16_000, 64).size());
    }

    private static DecodedVector decode(byte[] wire, LogicalType type, int count) {
        return decode(wire, type, count, 1_000_000, 64);
    }

    private static DecodedVector decode(byte[] wire, LogicalType type, int count, long budget, int depth) {
        BinaryReader reader = new BinaryReader(wire, new DecodeLimits(100_000, budget, depth));
        DecodedVector vector = VectorCodec.decodeVector(reader, type, count);
        reader.assertEof();
        assertEquals(count, vector.size());
        return vector;
    }

    private static void assertBudgetFailure(byte[] wire, LogicalType type, int count, long budget) {
        QuackProtocolException error = assertThrows(QuackProtocolException.class,
                () -> decode(wire, type, count, budget, 64));
        assertTrue(error.getMessage().contains("maxDecodedBytes"), error.getMessage());
    }

    private static byte[] flat(byte[] payload, Long validity) {
        Wire wire = new Wire().field(100).u(validity == null ? 0 : 1);
        if (validity != null) wire.field(101).blob(le64(validity));
        return wire.field(102).blob(payload).end().bytes();
    }

    private static byte[] constant(byte[] body) {
        return new Wire().field(90).u(2).raw(body).bytes();
    }

    private static byte[] dictionary(int[] selection, int count, byte[] body) {
        return new Wire().field(90).u(3).field(91).blob(le32(selection)).field(92).u(count).raw(body).bytes();
    }

    private static byte[] sequence() {
        return new Wire().field(90).u(4).field(91).u(10).field(92).u(2).end().bytes();
    }

    private static byte[] list(int[][] entries, int[] values, Long validity) {
        Wire wire = new Wire().field(100).u(validity == null ? 0 : 1);
        if (validity != null) wire.field(101).blob(le64(validity));
        wire.field(104).u(values.length).field(105).u(entries.length);
        for (int[] entry : entries) wire.field(100).u(entry[0]).field(101).u(entry[1]).end();
        return wire.field(106).raw(flat(le32(values), null)).end().bytes();
    }

    private static byte[] metadata(int kind, byte[] child) {
        Wire wire = new Wire().field(100).u(kind == 4 ? 101 : kind == 10 ? 3 : 100)
                .field(101).u(1).field(100).u(kind).field(200);
        if (kind == 5) wire.u(1).field(0).blob(new byte[]{'x'}).field(1);
        wire.raw(child);
        if (kind == 5) wire.end();
        if (kind == 10) wire.field(201).u(0);
        return wire.end().end().bytes();
    }

    private static byte[] intType() {
        return new Wire().field(100).u(13).end().bytes();
    }

    private static byte[] fetch(int chunks, int columns) {
        Wire wire = new Wire().field(1).u(8).field(3).u(0).end().field(1).u(chunks);
        for (int chunk = 0; chunk < chunks; chunk++) {
            wire.u(1).field(300).field(100).u(1).field(101).u(columns);
            for (int column = 0; column < columns; column++) wire.raw(intType());
            wire.field(102).u(columns);
            for (int column = 0; column < columns; column++) wire.raw(flat(le32(7), null));
            wire.end().end();
        }
        return wire.field(2).u(0).end().bytes();
    }

    private static byte[] le32(int... values) {
        ByteBuffer buffer = ByteBuffer.allocate(values.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int value : values) buffer.putInt(value);
        return buffer.array();
    }

    private static byte[] le64(long value) {
        return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array();
    }

    // Independent wire construction: no BinaryWriter or production encoder is used.
    private static final class Wire {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        Wire field(int id) {
            out.write(id & 255);
            out.write((id >>> 8) & 255);
            return this;
        }

        Wire u(long value) {
            do {
                int bits = (int) (value & 127);
                value >>>= 7;
                out.write(bits | (value == 0 ? 0 : 128));
            } while (value != 0);
            return this;
        }

        Wire raw(byte[] bytes) { out.writeBytes(bytes); return this; }
        Wire blob(byte[] bytes) { return u(bytes.length).raw(bytes); }
        Wire end() { return field(65535); }
        byte[] bytes() { return out.toByteArray(); }
    }
}

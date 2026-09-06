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
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

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
        // Structural allowance alone is insufficient: DATE conversion costs 32 per valid slot.
        LogicalType date = LogicalType.of(LogicalTypeId.DATE);
        byte[][] wires = {flat(le32(0, 1), null), sequence()};
        long[] exact = {336 + 2L * (32 + 4 + 32), 256 + 2L * (32 + 32)};
        for (int i = 0; i < wires.length; i++) {
            assertBudgetFailure(wires[i], date, 2, exact[i] - 1);
            assertEquals(2, decode(wires[i], date, 2, exact[i], 64).size());
        }
        // Reserve before attempting the unsupported infinite DATE conversion.
        assertBudgetFailure(flat(le32(Integer.MAX_VALUE), null), date, 1, 336 + 32 + 4 + 32 - 1);
    }

    @Test
    void fixedObjectBudgetsChargeOnlyValidSlotsAcrossMaskBoundaries() {
        for (FixedObjectCase fixture : fixedObjectCases()) {
            for (int count : new int[]{0, 63, 64, 65}) {
                for (Long pattern : new Long[]{null, 0L, 0x5555555555555555L, 0xaaaaaaaaaaaaaaaaL, -1L}) {
                    long[] validity = pattern == null ? null : new long[(count + 63) / 64];
                    if (validity != null) Arrays.fill(validity, pattern);
                    int validCount = pattern == null || pattern == -1L ? count : pattern == 0L ? 0
                            : pattern == 0x5555555555555555L ? (count + 1) / 2 : count / 2;
                    // Accounting policy, not measured heap usage. Padding mask bits are not rows.
                    long exact = 336 + (32L + fixture.payload().length) * count
                            + (long) fixture.cost() * validCount
                            + (validity == null ? 0 : 16L + 8L * validity.length);
                    assertAll("FLAT " + fixture.type().id() + ", width=" + fixture.payload().length
                            + ", count=" + count + ", mask=" + pattern, () -> {
                        byte[] wire = flat(fixedPayload(fixture, count, validity), validity);
                        assertBudgetFailure(wire, fixture.type(), count, exact - 1);
                        DecodedVector vector = decode(wire, fixture.type(), count, exact, 64);
                        assertInstanceOf(DecodedVector.ObjectVec.class, vector);
                        for (int row = 0; row < count; row++) {
                            boolean valid = validity == null || (validity[row / 64] & (1L << (row % 64))) != 0;
                            assertEquals(valid ? fixture.expected() : null, vector.getObject(row), "row " + row);
                            assertEquals(!valid, vector.isNull(row), "row " + row);
                        }
                    });
                }
            }
        }
    }

    @Test
    void sequenceBudgetsFollowLongConversionRatherThanFlatPhysicalWidth() {
        record SequenceCase(LogicalType type, int cost) {}
        List<SequenceCase> cases = new ArrayList<>();
        for (LogicalTypeId id : new LogicalTypeId[]{LogicalTypeId.DATE, LogicalTypeId.TIME,
                LogicalTypeId.TIME_NS, LogicalTypeId.TIME_TZ}) {
            cases.add(new SequenceCase(LogicalType.of(id), 32));
        }
        for (LogicalTypeId id : new LogicalTypeId[]{LogicalTypeId.TIMESTAMP_SEC, LogicalTypeId.TIMESTAMP_MS,
                LogicalTypeId.TIMESTAMP, LogicalTypeId.TIMESTAMP_NS, LogicalTypeId.TIMESTAMP_TZ}) {
            cases.add(new SequenceCase(LogicalType.of(id), 384));
        }
        for (int precision : new int[]{4, 9, 18, 38}) {
            // Even DECIMAL(38,2) uses BigInteger.valueOf(long), not the INT128 FLAT conversion.
            cases.add(new SequenceCase(LogicalType.decimal(precision, 2), 160));
        }
        cases.add(new SequenceCase(LogicalType.of(LogicalTypeId.UBIGINT), 512));
        cases.add(new SequenceCase(INT, 0));
        cases.add(new SequenceCase(LogicalType.of(LogicalTypeId.BIGINT), 0));
        LocalDateTime epoch = LocalDateTime.of(1970, 1, 1, 0, 0);
        for (SequenceCase fixture : cases) {
            for (int count : new int[]{0, 63, 64, 65}) {
                long exact = 256 + (32L + fixture.cost()) * count;
                assertAll("SEQUENCE " + fixture.type() + ", count=" + count, () -> {
                    byte[] wire = sequence();
                    assertBudgetFailure(wire, fixture.type(), count, exact - 1);
                    DecodedVector vector = decode(wire, fixture.type(), count, exact, 64);
                    Class<? extends DecodedVector> vectorClass = switch (fixture.type().id()) {
                        case INTEGER -> DecodedVector.IntVec.class;
                        case BIGINT -> DecodedVector.LongVec.class;
                        default -> DecodedVector.ObjectVec.class;
                    };
                    assertInstanceOf(vectorClass, vector);
                    for (int row = 0; row < count; row++) {
                        long value = 10L + 2L * row;
                        Object expected = switch (fixture.type().id()) {
                            case INTEGER -> (int) value;
                            case DATE -> LocalDate.ofEpochDay(value);
                            case TIME -> LocalTime.ofNanoOfDay(value * 1_000);
                            case TIME_NS -> LocalTime.ofNanoOfDay(value);
                            case TIMESTAMP_SEC -> epoch.plusSeconds(value);
                            case TIMESTAMP_MS -> epoch.plusNanos(value * 1_000_000);
                            case TIMESTAMP -> epoch.plusNanos(value * 1_000);
                            case TIMESTAMP_NS -> epoch.plusNanos(value);
                            case TIMESTAMP_TZ -> epoch.atOffset(ZoneOffset.UTC).plusNanos(value * 1_000);
                            case DECIMAL -> BigDecimal.valueOf(value, 2);
                            case UBIGINT -> BigInteger.valueOf(value);
                            default -> value;
                        };
                        assertEquals(expected, vector.getObject(row), "row " + row);
                        assertFalse(vector.isNull(row), "row " + row);
                    }
                });
            }
        }
        // SLEB -1 is invalid TIME: the full scalar allowance must be reserved before converting row 0.
        byte[] poisoned = new Wire().field(90).u(4).field(91).raw(new byte[]{0x7f})
                .field(92).u(2).end().bytes();
        assertBudgetFailure(poisoned, LogicalType.of(LogicalTypeId.TIME), 2, 256 + 2L * (32 + 32) - 1);
        assertThrows(QuackProtocolException.class, () -> decode(sequence(), LogicalType.decimal(39, 0), 1));
    }

    @Test
    void compressedObjectBudgetsChargeSourcesOnceAndPreserveReferenceIdentity() {
        for (FixedObjectCase fixture : fixedObjectCases()) {
            int width = fixture.payload().length;
            for (int count : new int[]{0, 63, 64, 65}) {
                for (boolean nullSource : new boolean[]{false, true}) {
                    int sourceCount = count == 0 ? 0 : 1;
                    long[] validity = nullSource ? new long[(sourceCount + 63) / 64] : null;
                    // Empty constants have a zero-row source: no source row, payload bytes or scalar cost.
                    long exact = (count == 0 ? 464 : 496 + 32L * count + width
                            + (nullSource ? 0 : fixture.cost()))
                            + (validity == null ? 0 : 16L + 8L * validity.length);
                    assertAll("CONSTANT " + fixture.type().id() + ", width=" + width
                            + ", count=" + count + ", null=" + nullSource, () -> {
                        byte[] wire = constant(flat(fixedPayload(fixture, sourceCount, validity), validity));
                        assertBudgetFailure(wire, fixture.type(), count, exact - 1);
                        DecodedVector vector = decode(wire, fixture.type(), count, exact, 64);
                        for (int row = 0; row < count; row++) {
                            assertEquals(nullSource ? null : fixture.expected(), vector.getObject(row), "row " + row);
                            assertEquals(nullSource, vector.isNull(row), "row " + row);
                            assertSame(vector.getObject(0), vector.getObject(row), "row " + row);
                        }
                    });
                }
                // Only entries 0, 1 and 64 are selected. All 65 source entries must still be charged,
                // including when the output is empty; repeated selections only copy references.
                int sourceCount = 65;
                int[] selection = new int[count];
                for (int row = 0; row < count; row++) selection[row] = row % 3 == 2 ? 64 : row % 3;
                for (Long pattern : new Long[]{null, 0L, 0x5555555555555555L, 0xaaaaaaaaaaaaaaaaL, -1L}) {
                    long[] validity = pattern == null ? null : new long[(sourceCount + 63) / 64];
                    if (validity != null) Arrays.fill(validity, pattern);
                    int validCount = pattern == null || pattern == -1L ? sourceCount : pattern == 0L ? 0
                            : pattern == 0x5555555555555555L ? 33 : 32;
                    long exact = 480 + 36L * count + (32L + width) * sourceCount
                            + (long) fixture.cost() * validCount
                            + (validity == null ? 0 : 16L + 8L * validity.length);
                    assertAll("DICTIONARY " + fixture.type().id() + ", width=" + width
                            + ", count=" + count + ", mask=" + pattern, () -> {
                        byte[] wire = dictionary(selection, sourceCount,
                                flat(fixedPayload(fixture, sourceCount, validity), validity));
                        assertBudgetFailure(wire, fixture.type(), count, exact - 1);
                        DecodedVector vector = decode(wire, fixture.type(), count, exact, 64);
                        for (int row = 0; row < count; row++) {
                            int index = selection[row];
                            boolean valid = validity == null || (validity[index / 64] & (1L << (index % 64))) != 0;
                            assertEquals(valid ? fixture.expected() : null, vector.getObject(row), "row " + row);
                            assertEquals(!valid, vector.isNull(row), "row " + row);
                            assertSame(vector.getObject(row % 3), vector.getObject(row), "row " + row);
                        }
                    });
                }
            }
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

    private record FixedObjectCase(LogicalType type, int cost, byte[] payload, Object expected) {}

    private static List<FixedObjectCase> fixedObjectCases() {
        LocalDateTime epoch = LocalDateTime.of(1970, 1, 1, 0, 0);
        // Explicit policy costs and independent physical bytes, not production cost/encoding helpers.
        return List.of(
                new FixedObjectCase(LogicalType.of(LogicalTypeId.DATE), 32, le32(7), LocalDate.of(1970, 1, 8)),
                new FixedObjectCase(LogicalType.of(LogicalTypeId.TIME), 32, le64(257), LocalTime.ofNanoOfDay(257_000)),
                new FixedObjectCase(LogicalType.of(LogicalTypeId.TIME_NS), 32, le64(257), LocalTime.ofNanoOfDay(257)),
                new FixedObjectCase(LogicalType.of(LogicalTypeId.TIME_TZ), 32, le64(257), 257L),
                new FixedObjectCase(LogicalType.of(LogicalTypeId.UUID), 32, le64(-1L, Long.MIN_VALUE), new UUID(0, -1L)),
                new FixedObjectCase(LogicalType.of(LogicalTypeId.INTERVAL), 32,
                        new Wire().raw(le32(2, 3)).raw(le64(257)).bytes(), new IntervalValue(2, 3, 257)),
                new FixedObjectCase(LogicalType.of(LogicalTypeId.ENUM,
                        new ExtraTypeInfo.EnumInfo(List.of("label"), Optional.empty())), 64, new byte[]{0}, "label"),
                new FixedObjectCase(LogicalType.of(LogicalTypeId.ENUM,
                        new ExtraTypeInfo.EnumInfo(Collections.nCopies(256, "label"), Optional.empty())),
                        64, new byte[]{0, 0}, "label"),
                new FixedObjectCase(LogicalType.of(LogicalTypeId.ENUM,
                        new ExtraTypeInfo.EnumInfo(Collections.nCopies(65_536, "label"), Optional.empty())),
                        64, le32(0), "label"),
                new FixedObjectCase(LogicalType.decimal(4, 2), 160, new byte[]{1, 1}, new BigDecimal("2.57")),
                new FixedObjectCase(LogicalType.decimal(9, 2), 160, le32(257), new BigDecimal("2.57")),
                new FixedObjectCase(LogicalType.decimal(18, 2), 160, le64(257), new BigDecimal("2.57")),
                new FixedObjectCase(LogicalType.decimal(38, 2), 640, le64(257, 1),
                        new BigDecimal("184467440737095518.73")),
                new FixedObjectCase(LogicalType.of(LogicalTypeId.HUGEINT), 576, le64(257, 1),
                        new BigInteger("18446744073709551873")),
                new FixedObjectCase(LogicalType.of(LogicalTypeId.UHUGEINT), 768, le64(-1L, -1L),
                        new BigInteger("340282366920938463463374607431768211455")),
                new FixedObjectCase(LogicalType.of(LogicalTypeId.UBIGINT), 512, le64(-1L),
                        new BigInteger("18446744073709551615")),
                new FixedObjectCase(LogicalType.of(LogicalTypeId.TIMESTAMP_SEC), 384, le64(257), epoch.plusSeconds(257)),
                new FixedObjectCase(LogicalType.of(LogicalTypeId.TIMESTAMP_MS), 384, le64(257), epoch.plusNanos(257_000_000)),
                new FixedObjectCase(LogicalType.of(LogicalTypeId.TIMESTAMP), 384, le64(257), epoch.plusNanos(257_000)),
                new FixedObjectCase(LogicalType.of(LogicalTypeId.TIMESTAMP_NS), 384, le64(257), epoch.plusNanos(257)),
                new FixedObjectCase(LogicalType.of(LogicalTypeId.TIMESTAMP_TZ), 384, le64(257),
                        epoch.atOffset(ZoneOffset.UTC).plusNanos(257_000)));
    }

    private static byte[] fixedPayload(FixedObjectCase fixture, int count, long[] validity) {
        byte[] poison = new byte[fixture.payload().length];
        Arrays.fill(poison, (byte) 0xff);
        poison = switch (fixture.type().id()) {
            case DATE -> le32(Integer.MAX_VALUE);
            case TIMESTAMP_SEC, TIMESTAMP_MS, TIMESTAMP, TIMESTAMP_NS, TIMESTAMP_TZ -> le64(Long.MAX_VALUE);
            default -> poison; // Invalid ENUM indices and negative TIME/TIME_NS values.
        };
        ByteBuffer payload = ByteBuffer.allocate(fixture.payload().length * count);
        for (int row = 0; row < count; row++) {
            boolean valid = validity == null || (validity[row / 64] & (1L << (row % 64))) != 0;
            payload.put(valid ? fixture.payload() : poison);
        }
        return payload.array();
    }

    private static byte[] flat(byte[] payload, long... validity) {
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

    private static byte[] le64(long... values) {
        ByteBuffer buffer = ByteBuffer.allocate(values.length * 8).order(ByteOrder.LITTLE_ENDIAN);
        for (long value : values) buffer.putLong(value);
        return buffer.array();
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

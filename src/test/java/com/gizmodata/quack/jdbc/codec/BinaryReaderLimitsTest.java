package com.gizmodata.quack.jdbc.codec;

import com.gizmodata.quack.jdbc.QuackProtocolException;
import com.gizmodata.quack.jdbc.message.VectorCodec;
import com.gizmodata.quack.jdbc.type.LogicalType;
import com.gizmodata.quack.jdbc.type.LogicalTypeId;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class BinaryReaderLimitsTest {
    @Test
    void validatesConfigurationAndResponseBoundary() {
        assertEquals(new DecodeLimits(64 * 1024 * 1024, 256L * 1024 * 1024, 64), DecodeLimits.DEFAULT);
        for (int[] limits : new int[][]{{0, 1, 1}, {-1, 1, 1}, {1, 0, 1}, {1, -1, 1},
                {1, 1, 0}, {1, 1, 129}}) {
            assertThrows(IllegalArgumentException.class,
                    () -> new DecodeLimits(limits[0], limits[1], limits[2]));
        }
        assertDoesNotThrow(() -> new DecodeLimits(1, Long.MAX_VALUE, 128));
        DecodeLimits limits = new DecodeLimits(2, 256, 1);
        assertEquals(2, new BinaryReader(new byte[2], limits).remaining());
        assertThrows(QuackProtocolException.class, () -> new BinaryReader(new byte[3], limits));
    }

    @Test
    void lebTenthByteChecksAllTerminalBitsButAcceptsExtremaAndNonminimalForms() {
        for (int terminal = 0; terminal <= 255; terminal++) {
            byte[] wire = new byte[10];
            Arrays.fill(wire, (byte) 0x80);
            wire[9] = (byte) terminal;
            if (terminal == 0 || terminal == 1) {
                assertEquals(terminal == 0 ? 0L : Long.MIN_VALUE, new BinaryReader(wire).readUlebLong());
            } else {
                assertThrows(QuackProtocolException.class, () -> new BinaryReader(wire).readUlebLong(),
                        "ULEB terminal " + terminal);
            }
            if (terminal == 0 || terminal == 127) {
                assertEquals(terminal == 0 ? 0L : Long.MIN_VALUE, new BinaryReader(wire).readSlebLong());
            } else {
                assertThrows(QuackProtocolException.class, () -> new BinaryReader(wire).readSlebLong(),
                        "SLEB terminal " + terminal);
            }
        }
        assertEquals(-1L, reader("ffffffffffffffffff01", 1024).readUlebLong());
        assertEquals(Long.MAX_VALUE, reader("ffffffffffffffff7f", 1024).readUlebLong());
        assertEquals(Long.MAX_VALUE, reader("ffffffffffffffffff00", 1024).readSlebLong());
        assertEquals(-1L, reader("ffffffffffffffffff7f", 1024).readSlebLong());
        assertEquals(1L, reader("8100", 1024).readUlebLong());
        assertEquals(1L, reader("8100", 1024).readSlebLong());
        assertEquals(-1L, reader("ff7f", 1024).readSlebLong());
        assertEquals(Integer.MAX_VALUE, reader("ffffffff07", 1024).readUlebInt());
        for (String wire : List.of("8080808008", "ffffffffffffffffff01", "80")) {
            assertThrows(QuackProtocolException.class, () -> reader(wire, 1024).readUlebInt());
        }
    }

    @Test
    void listCardinalityAndBudgetAreCheckedBeforeReadingElements() {
        AtomicInteger calls = new AtomicInteger();
        for (int expected : new int[]{0, 1, 3, -2}) {
            BinaryReader reader = reader("020708", 64);
            assertThrows(QuackProtocolException.class,
                    () -> reader.readList(expected, i -> calls.incrementAndGet()));
            assertEquals(1, reader.position());
        }
        assertEquals(0, calls.get());
        BinaryReader shortBudget = reader("020708", 63);
        assertThrows(QuackProtocolException.class,
                () -> shortBudget.readList(i -> calls.incrementAndGet()));
        assertEquals(0, calls.get());
        BinaryReader exact = reader("020708", 64);
        assertEquals(List.of(7, 8), exact.readList(2, i -> exact.readByte()));
        exact.assertEof();
        assertThrows(QuackProtocolException.class, () -> exact.reserve(1));
        assertEquals(List.of(), reader("00", 32).readList(i -> fail("empty list callback")));
    }

    @Test
    void bytesStringsAndObjectsChargeTheirAllocationAtTheBoundary() {
        BinaryReader bytes = reader("010203", 19);
        assertArrayEquals(new byte[]{1, 2, 3}, bytes.readBytes(3));
        assertThrows(QuackProtocolException.class, () -> bytes.reserve(1));
        assertThrows(QuackProtocolException.class, () -> reader("010203", 18).readBytes(3));
        BinaryReader truncated = reader("0102", 1024);
        assertThrows(QuackProtocolException.class, () -> truncated.readBytes(3));
        assertThrows(QuackProtocolException.class, () -> truncated.readBytes(-1));
        assertEquals(0, truncated.position());
        assertThrows(QuackProtocolException.class, () -> reader("02", 1024).readBlob(3));
        // Three UTF-8 bytes: byte array 16+3, then String allowance 64+4*3.
        BinaryReader string = reader("0361c3a9", 95);
        assertEquals("a\u00e9", string.readString());
        assertThrows(QuackProtocolException.class, () -> string.reserve(1));
        assertThrows(QuackProtocolException.class, () -> reader("0361c3a9", 94).readString());
        BinaryReader object = reader("ffff", 128);
        assertEquals("ok", object.readObject(() -> "ok"));
        assertThrows(QuackProtocolException.class, () -> object.reserve(1));
        assertThrows(QuackProtocolException.class,
                () -> reader("ffff", 127).readObject(() -> fail("body must not run")));
    }

    @Test
    void subReadersShareAllocationAndActiveDepthButSiblingsAndFailuresReleaseDepth() {
        BinaryReader parent = new BinaryReader(new byte[0], new DecodeLimits(1024, 256, 2));
        parent.reserve(100);
        BinaryReader child = parent.subReader(new byte[0]); // 64 bytes for the reader
        child.reserve(92);
        assertThrows(QuackProtocolException.class, () -> parent.reserve(1));
        assertThrows(QuackProtocolException.class, () -> child.reserve(1));
        assertThrows(QuackProtocolException.class, () -> child.reserve(-1));
        assertThrows(QuackProtocolException.class, () -> child.reserve(Long.MAX_VALUE));
        for (int i = 0; i < 100; i++) {
            assertEquals(7, parent.nested(() -> child.nested(() -> 7)));
        }
        assertThrows(QuackProtocolException.class,
                () -> parent.nested(() -> child.nested(() -> parent.nested(() -> 7))));
        assertThrows(IllegalStateException.class,
                () -> parent.nested(() -> child.nested(() -> { throw new IllegalStateException(); })));
        assertEquals(7, child.nested(() -> parent.nested(() -> 7)));
    }

    @Test
    void originalTinyPayloadHazardsFailInBoundedSubprocesses() throws Exception {
        for (String probe : List.of("maxlen", "offset", "constant")) {
            Process process = new ProcessBuilder(
                    Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                     "-Xmx32m", "-Xss256k", "-XX:+ExitOnOutOfMemoryError", "-cp",
                    System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                    BinaryReaderLimitsTest.class.getName(), probe).redirectErrorStream(true).start();
            try {
                assertTrue(process.waitFor(10, TimeUnit.SECONDS), probe + " timed out");
                String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                assertEquals(0, process.exitValue(), probe + ": " + output);
                assertTrue(output.contains("rejected " + probe), output);
            } finally {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        }
    }

    // Never catch Error here: OOM/stack overflow must fail the child, not Maven's JVM.
    public static void main(String[] args) {
        String expected = switch (args[0]) {
            case "maxlen" -> "maxDecodedBytes";
            case "offset" -> "Unexpected end of input";
            case "constant" -> "maxNestingDepth";
            default -> throw new IllegalArgumentException(args[0]);
        };
        try {
            switch (args[0]) {
                case "maxlen" -> new BinaryReader(HexFormat.of().parseHex("ffffffff07"))
                        .readList(i -> { throw new AssertionError("element reader invoked"); });
                case "offset" -> {
                    BinaryReader reader = new BinaryReader(new byte[]{0});
                    reader.readByte();
                    reader.readBytes(Integer.MAX_VALUE);
                }
                case "constant" -> {
                    ByteArrayOutputStream wire = new ByteArrayOutputStream();
                    for (int i = 0; i < 20_000; i++) wire.writeBytes(new byte[]{90, 0, 2});
                    wire.writeBytes(HexFormat.of().parseHex("6400006600042a000000ffff"));
                    VectorCodec.decodeVector(new BinaryReader(wire.toByteArray()),
                            LogicalType.of(LogicalTypeId.INTEGER), 1);
                }
                default -> throw new IllegalArgumentException(args[0]);
            }
            throw new AssertionError("malformed input accepted");
        } catch (QuackProtocolException expectedFailure) {
            if (!expectedFailure.getMessage().contains(expected)) throw expectedFailure;
            System.out.println("rejected " + args[0]);
        }
    }

    private static BinaryReader reader(String hex, long budget) {
        return new BinaryReader(HexFormat.of().parseHex(hex), new DecodeLimits(1024, budget, 64));
    }
}

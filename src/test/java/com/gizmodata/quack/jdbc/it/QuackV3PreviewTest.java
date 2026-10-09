package com.gizmodata.quack.jdbc.it;

import com.gizmodata.quack.jdbc.message.DataChunk;
import com.gizmodata.quack.jdbc.message.DecodedVector;
import com.gizmodata.quack.jdbc.sql.QuackConnection;
import com.gizmodata.quack.jdbc.transport.QuackUri;
import com.gizmodata.quack.jdbc.type.LogicalType;
import com.gizmodata.quack.jdbc.type.LogicalTypeId;
import org.junit.jupiter.api.Test;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Optional real-wire test; pass a CLI and its exactly matching Quack extension. */
class QuackV3PreviewTest {
    @Test
    void queryFetchAndSendDataAgainstPreview() throws Exception {
        String cli = System.getenv("QUACK_V3_IT_DUCKDB");
        String extension = System.getenv("QUACK_V3_IT_EXTENSION");
        assumeTrue(cli != null && extension != null,
                "Set QUACK_V3_IT_DUCKDB and QUACK_V3_IT_EXTENSION to matching preview artifacts");
        Path home = Files.createTempDirectory(Path.of("target"), "quack-v3-").toAbsolutePath();
        Path init = Files.createFile(home.resolve("init.sql"));
        Path log = home.resolve("duckdb.log");
        int port;
        try (ServerSocket socket = new ServerSocket(0)) { port = socket.getLocalPort(); }
        ProcessBuilder builder = new ProcessBuilder(cli, "-unsigned", "-init", init.toString(),
                "-batch", "-bail", ":memory:").redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().put("HOME", home.toString());
        builder.environment().put("XDG_CONFIG_HOME", home.toString());
        Process process = builder.start();
        try (BufferedWriter stdin = new BufferedWriter(
                new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8))) {
            stdin.write("LOAD '" + extension.replace("'", "''") + "';\n");
            stdin.write("CALL quack_serve('quack:127.0.0.1:" + port
                    + "', token=>'v3-test-token');\n");
            stdin.flush();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
            boolean ready = false;
            while (process.isAlive() && System.nanoTime() < deadline) {
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress("127.0.0.1", port), 200);
                    ready = true;
                    break;
                } catch (IOException ignored) { Thread.sleep(100); }
            }
            assertTrue(ready, () -> "Quack preview did not start: " + readLog(log));

            QuackUri uri = QuackUri.parse("jdbc:quack://127.0.0.1:" + port + "?token=v3-test-token");
            try (QuackConnection connection = new QuackConnection(uri);
                 var statement = connection.createStatement()) {
                assertEquals(3, connection.session().protocolVersion());
                statement.execute("CREATE TABLE t(i INTEGER, name VARCHAR)");
                LogicalType integer = LogicalType.of(LogicalTypeId.INTEGER);
                LogicalType varchar = LogicalType.of(LogicalTypeId.VARCHAR);
                DataChunk chunk = new DataChunk(2, List.of(integer, varchar), List.of(
                        new DecodedVector.IntVec(integer, new int[]{1, 2}, null),
                        new DecodedVector.ObjectVec(varchar, new Object[]{"one", "é"})));
                connection.session().appendChunk("main", "t", chunk);
                try (ResultSet rows = statement.executeQuery("SELECT i, name FROM t ORDER BY i")) {
                    assertTrue(rows.next());
                    assertEquals(1, rows.getInt(1));
                    assertEquals("one", rows.getString(2));
                    assertTrue(rows.next());
                    assertEquals(2, rows.getInt(1));
                    assertEquals("é", rows.getString(2));
                    assertFalse(rows.next());
                }
                try (ResultSet rows = statement.executeQuery(
                        "SELECT i::VARCHAR FROM range(10000) t(i)")) {
                    int count = 0;
                    while (rows.next()) assertEquals(Integer.toString(count++), rows.getString(1));
                    assertEquals(10000, count);
                }
            }
        } finally {
            process.destroy();
            if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly();
        }
    }

    private static String readLog(Path log) {
        try { return Files.readString(log); } catch (IOException e) { return e.toString(); }
    }
}

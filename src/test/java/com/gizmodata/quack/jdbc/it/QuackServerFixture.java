package com.gizmodata.quack.jdbc.it;

import com.gizmodata.quack.jdbc.message.MessageCodec;
import com.gizmodata.quack.jdbc.message.MessageHeader;
import com.gizmodata.quack.jdbc.message.MessageType;
import com.gizmodata.quack.jdbc.message.QuackMessage;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * Isolated DuckDB 1.5.5 servers. Optional local runs may use signed stock Quack;
 * {@code -Dquack.it.required=true} requires a CLI and a patched artifact.
 * {@code QUACK_IT_DUCKDB} selects the CLI and
 * {@code QUACK_IT_RESULT_METADATA_EXTENSION} selects the local test artifact.
 */
public final class QuackServerFixture implements AutoCloseable {
    static final String DUCKDB_VERSION = "v1.5.5";
    private static final String QUACK_REVISION = "c1548111c1bfd16207e22fd3cb7e4bde1335b9d0";
    private static final String DEFAULT_TOKEN = "quack-jdbc-it-token";
    private static final String METADATA_HEADER = "X-Quack-Result-Metadata";

    private final Process process;
    private final int port;
    private final BufferedWriter stdin;
    private final Path logFile;
    private final Path extensionDirectory;
    private final Path artifact;

    private QuackServerFixture(Process process, int port, BufferedWriter stdin, Path logFile,
                               Path extensionDirectory, Path artifact) {
        this.process = process;
        this.port = port;
        this.stdin = stdin;
        this.logFile = logFile;
        this.extensionDirectory = extensionDirectory;
        this.artifact = artifact;
    }

    static boolean required() {
        return Boolean.getBoolean("quack.it.required");
    }

    static boolean enabled(boolean required, String binary, String path) {
        return required || resolveExecutable(binary, path) != null;
    }

    public static QuackServerFixture tryStart() throws IOException, InterruptedException {
        return start(false);
    }

    /** Always uses the official signed core extension, even when a patched artifact is configured. */
    public static QuackServerFixture tryStartLegacy() throws IOException, InterruptedException {
        return start(true);
    }

    private static QuackServerFixture start(boolean legacy) throws IOException, InterruptedException {
        Path duckdb = requireExecutable(System.getenv().getOrDefault("QUACK_IT_DUCKDB", "duckdb"),
                System.getenv("PATH"), required());
        if (duckdb == null) return null;
        Path artifact = resultMetadataArtifact(legacy,
                System.getenv("QUACK_IT_RESULT_METADATA_EXTENSION"), required());
        Path logs = Path.of("target", "quack-fixture-logs").toAbsolutePath();
        Files.createDirectories(logs);
        Path home = Files.createTempDirectory(logs, artifact == null ? "stock-" : "metadata-");
        Path extensions = Files.createDirectory(home.resolve("extensions"));
        Path log = home.resolve("server.log");
        Path versionLog = home.resolve("cli-version.log");
        Files.writeString(log, "CLI=" + duckdb + "\nartifact=" + artifact
                + "\nallow_unsigned_extensions=" + (artifact != null)
                + "\nextension_directory=" + extensions + "\n");
        System.out.println("Quack fixture: CLI=" + duckdb + ", artifact=" + artifact + ", log=" + log);

        ProcessBuilder versionBuilder = isolatedProcess(List.of(duckdb.toString(), "--version"), home);
        Process version = versionBuilder.redirectOutput(versionLog.toFile()).start();
        try {
            if (!version.waitFor(10, TimeUnit.SECONDS)) {
                throw new IOException("DuckDB --version timed out; see " + versionLog);
            }
            String output = Files.readString(versionLog).trim();
            Files.writeString(log, "CLI version=" + output + "\n", StandardOpenOption.APPEND);
            System.out.println("Quack fixture CLI version: " + output);
            validateCliVersion(version.exitValue(), output);
        } finally {
            if (version.isAlive()) version.destroyForcibly();
        }

        int port = pickFreePort();
        List<String> command = new ArrayList<>(List.of(duckdb.toString()));
        if (artifact != null) command.add("-unsigned");
        Path init = Files.createFile(home.resolve("empty-init.sql"));
        command.addAll(List.of("-init", init.toString(), "-batch", "-bail", ":memory:"));
        Process process = isolatedProcess(command, home)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile())).start();
        BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        QuackServerFixture fixture = new QuackServerFixture(process, port, writer, log, extensions, artifact);
        try {
            writer.write("SET extension_directory='" + sql(extensions.toString()) + "';\n");
            writer.write("SET autoinstall_known_extensions=false;\nSET autoload_known_extensions=false;\n");
            // httpfs supplies the crypto provider needed by Quack's session-ID RNG.
            writer.write("INSTALL httpfs FROM core;\nLOAD httpfs;\n");
            if (artifact == null) {
                writer.write("INSTALL quack FROM core;\nLOAD quack;\n");
            } else {
                writer.write("LOAD '" + sql(artifact.toString()) + "';\n");
            }
            writer.write("CALL quack_serve('quack:127.0.0.1:" + port + "', token=>'" + DEFAULT_TOKEN + "');\n");
            writer.flush();
            if (!fixture.waitForReady(120_000)) {
                throw new IOException("Quack server did not become ready; see " + log);
            }
            fixture.verifyHttpIdentity();
            fixture.verifyJdbcIdentity();
            return fixture;
        } catch (InterruptedException e) {
            fixture.close();
            Thread.currentThread().interrupt();
            throw e;
        } catch (IOException | RuntimeException e) {
            fixture.close();
            throw new IOException("Quack fixture qualification failed; see " + log, e);
        }
    }

    private static ProcessBuilder isolatedProcess(List<String> command, Path home) {
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().put("HOME", home.toString());
        builder.environment().put("XDG_CONFIG_HOME", home.toString());
        builder.environment().remove("LD_PRELOAD");
        builder.environment().remove("LD_LIBRARY_PATH");
        return builder;
    }

    private void verifyHttpIdentity() throws IOException, InterruptedException {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        QuackMessage.ConnectionRequest connect = new QuackMessage.ConnectionRequest(
                MessageHeader.of(MessageType.CONNECTION_REQUEST).withClientQueryId(1),
                Optional.of(DEFAULT_TOKEN), Optional.of("quack-fixture"), Optional.empty(),
                Optional.of(1L), Optional.of(1L));
        HttpResponse<byte[]> response = post(client, connect);
        if (response.statusCode() != 200) {
            throw new IOException("HTTP identity probe: " + response.statusCode() + " " + response.headers());
        }
        QuackMessage decoded = MessageCodec.decode(response.body());
        if (!(decoded instanceof QuackMessage.ConnectionResponse connection)) {
            throw new IOException("Expected v1 CONNECTION_RESPONSE, got " + decoded);
        }
        try {
            if (!connection.serverDuckdbVersion().equals(Optional.of(DUCKDB_VERSION))
                    || !connection.quackVersion().equals(Optional.of(1L))) {
                throw new IOException("Unexpected HTTP server/wire identity: " + connection);
            }
            String capability = response.headers().firstValue(METADATA_HEADER).orElse("");
            if (artifact != null && !"1".equals(capability)) {
                throw new IOException("Patched fixture did not advertise " + METADATA_HEADER + ": 1");
            }
            Files.writeString(logFile, "HTTP server=" + DUCKDB_VERSION + ", wire=1, "
                    + METADATA_HEADER + "=" + capability + "\n", StandardOpenOption.APPEND);
        } finally {
            post(client, new QuackMessage.DisconnectMessage(
                    MessageHeader.of(MessageType.DISCONNECT_MESSAGE)
                            .withConnectionId(connection.header().connectionId().orElseThrow())
                            .withClientQueryId(2)));
        }
    }

    private HttpResponse<byte[]> post(HttpClient client, QuackMessage message)
            throws IOException, InterruptedException {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/quack"))
                .timeout(Duration.ofSeconds(15)).header("Content-Type", "application/vnd.duckdb")
                .header(METADATA_HEADER, "1")
                .POST(HttpRequest.BodyPublishers.ofByteArray(MessageCodec.encode(message))).build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    private void verifyJdbcIdentity() throws IOException {
        try (var connection = DriverManager.getConnection(jdbcUrl());
             var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT version(), current_setting('allow_unsigned_extensions'), "
                     + "current_setting('force_mbedtls_unsafe'), extension_name, loaded, install_path, "
                     + "extension_version, install_mode, installed_from FROM duckdb_extensions() "
                     + "WHERE extension_name IN ('quack', 'httpfs') ORDER BY extension_name")) {
            int count = 0;
            while (rows.next()) {
                Identity identity = new Identity(rows.getString(1), rows.getBoolean(2), rows.getBoolean(3),
                        rows.getString(4), rows.getBoolean(5), rows.getString(6), rows.getString(7),
                        rows.getString(8), rows.getString(9));
                Files.writeString(logFile, "JDBC identity=" + identity + "\n", StandardOpenOption.APPEND);
                System.out.println("Quack fixture JDBC identity: " + identity);
                validateIdentity(identity, artifact, extensionDirectory);
                count++;
            }
            if (count != 2) throw new IOException("Expected loaded Quack and httpfs, found " + count);
        } catch (SQLException e) {
            throw new IOException("JDBC default/legacy profile qualification failed", e);
        }
    }

    record Identity(String runtime, boolean unsigned, boolean unsafeCrypto, String name, boolean loaded,
                    String installPath, String version, String installMode, String installedFrom) {}

    static void validateIdentity(Identity identity, Path artifact, Path extensionDirectory) throws IOException {
        if (!DUCKDB_VERSION.equals(identity.runtime()) || identity.unsigned() != (artifact != null)
                || identity.unsafeCrypto() || !identity.loaded()
                || identity.version() == null || identity.version().isBlank()) {
            throw new IOException("Invalid fixture runtime/signature/extension identity: " + identity);
        }
        if (artifact != null && "quack".equals(identity.name())) {
            if (identity.version().length() < 7 || !QUACK_REVISION.startsWith(identity.version())) {
                throw new IOException("Unexpected patched Quack source revision: " + identity);
            }
            // DuckDB reports NOT_INSTALLED with an empty path for an explicit LOAD.
            // A cached install must never be mistaken for the artifact loaded above.
            if ("NOT_INSTALLED".equals(identity.installMode()) && "".equals(identity.installPath())) return;
            if (identity.installPath() != null && !identity.installPath().isBlank()
                    && Path.of(identity.installPath()).toRealPath().equals(artifact.toRealPath())) return;
            throw new IOException("Patched Quack path does not match explicit artifact: " + identity);
        }
        if (!("quack".equals(identity.name()) || "httpfs".equals(identity.name()))
                || !"REPOSITORY".equals(identity.installMode()) || !"core".equals(identity.installedFrom())
                || identity.installPath() == null || identity.installPath().isBlank()
                || !Path.of(identity.installPath()).toRealPath().startsWith(extensionDirectory.toRealPath())) {
            throw new IOException("Expected isolated official core extension: " + identity);
        }
    }

    static void validateCliVersion(int exitCode, String output) throws IOException {
        if (exitCode != 0 || !output.matches("(?s)^v1\\.5\\.5(?:\\s.*)?$")) {
            throw new IOException("Expected official DuckDB CLI " + DUCKDB_VERSION + ", got: " + output);
        }
    }

    static Path resultMetadataArtifact(boolean legacy, String configured, boolean required) throws IOException {
        if (legacy) return null;
        if (configured == null || configured.isBlank()) {
            if (required) throw new IOException("Required integration tests need QUACK_IT_RESULT_METADATA_EXTENSION");
            return null;
        }
        Path path = Path.of(configured);
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
            throw new IOException("Invalid QUACK_IT_RESULT_METADATA_EXTENSION: " + configured);
        }
        return path.toRealPath();
    }

    static Path requireExecutable(String binary, String path, boolean required) throws IOException {
        Path resolved = resolveExecutable(binary, path);
        if (resolved == null && required) {
            throw new IOException("Required integration tests need an executable DuckDB CLI; QUACK_IT_DUCKDB=" + binary);
        }
        return resolved;
    }

    static Path resolveExecutable(String binary, String path) {
        if (binary == null || binary.isBlank()) return null;
        Path direct = Path.of(binary);
        List<Path> candidates = new ArrayList<>();
        if (direct.isAbsolute() || direct.getParent() != null) {
            candidates.add(direct);
        } else if (path != null) {
            for (String entry : path.split(java.io.File.pathSeparator, -1)) {
                candidates.add(Path.of(entry, binary));
            }
        }
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                try { return candidate.toRealPath(); } catch (IOException ignored) { }
            }
        }
        return null;
    }

    public String jdbcUrl() {
        return "jdbc:quack://127.0.0.1:" + port + "?token=" + DEFAULT_TOKEN
                + (artifact == null ? "&resultMetadata=legacy" : "");
    }

    public int port() { return port; }
    public String token() { return DEFAULT_TOKEN; }
    public Path logFile() { return logFile; }

    @Override
    public void close() {
        try {
            stdin.write("CALL quack_stop('quack:127.0.0.1:" + port + "');\n.quit\n");
            stdin.flush();
            stdin.close();
        } catch (IOException ignored) { }
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private static String sql(String value) { return value.replace("'", "''"); }

    private static int pickFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) { return socket.getLocalPort(); }
    }

    private boolean waitForReady(long timeoutMillis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (process.isAlive() && System.nanoTime() < deadline) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", port), 500);
                return true;
            } catch (IOException ignored) { Thread.sleep(250); }
        }
        return false;
    }
}

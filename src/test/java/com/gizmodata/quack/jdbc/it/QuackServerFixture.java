package com.gizmodata.quack.jdbc.it;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Spawns an ordinary DuckDB CLI with signed core extensions in an isolated directory.
 *
 * <p>Skips (returns {@code null} from {@link #tryStart}) when the
 * {@code duckdb} binary cannot be located. Set {@code QUACK_IT_DUCKDB} to override
 * its path. {@code -Dquack.it.required=true} instead fails without a CLI and pins
 * DuckDB v1.5.6 and stock Quack 7e80f7f; optional local runs may use other versions.
 */
public final class QuackServerFixture implements AutoCloseable {

    static final String DUCKDB_VERSION = "v1.5.6";
    private static final String QUACK_REVISION = "7e80f7ffcc98d0b3e81d0e1df8cc1c2da240a64b";
    private static final String DEFAULT_TOKEN = "quack-jdbc-it-token";

    private final Process process;
    private final int port;
    private final BufferedWriter stdin;
    private final Path logFile;
    private final Path extensionDirectory;

    private QuackServerFixture(Process process, int port, BufferedWriter stdin,
                               Path logFile, Path extensionDirectory) {
        this.process = process;
        this.port = port;
        this.stdin = stdin;
        this.logFile = logFile;
        this.extensionDirectory = extensionDirectory;
    }

    static boolean required() {
        return Boolean.getBoolean("quack.it.required");
    }

    static boolean enabled(boolean required, String binary, String path) {
        return required || resolveExecutable(binary, path) != null;
    }

    public static QuackServerFixture tryStart() throws IOException, InterruptedException {
        boolean required = required();
        Path duckdb = requireExecutable(System.getenv().getOrDefault("QUACK_IT_DUCKDB", "duckdb"),
                System.getenv("PATH"), required);
        if (duckdb == null) return null;
        Path logs = Path.of("target", "quack-fixture-logs").toAbsolutePath();
        Files.createDirectories(logs);
        Path home = Files.createTempDirectory(logs, "stock-");
        Path extensions = Files.createDirectory(home.resolve("extensions"));
        Path log = home.resolve("server.log");
        Path versionLog = home.resolve("cli-version.log");
        Files.writeString(log, "CLI=" + duckdb + "\nrequired=" + required
                + "\nextension_directory=" + extensions + "\n");
        System.out.println("Quack fixture: CLI=" + duckdb + ", log=" + log);

        Process version = isolatedProcess(List.of(duckdb.toString(), "--version"), home)
                .redirectOutput(versionLog.toFile()).start();
        try {
            if (!version.waitFor(10, TimeUnit.SECONDS)) {
                throw new IOException("DuckDB --version timed out; see " + versionLog);
            }
            String output = Files.readString(versionLog).trim();
            Files.writeString(log, "CLI version=" + output + "\n", StandardOpenOption.APPEND);
            System.out.println("Quack fixture CLI version: " + output);
            validateCliVersion(version.exitValue(), output, required);
        } finally {
            if (version.isAlive()) version.destroyForcibly();
        }

        int port = pickFreePort();
        Path init = Files.createFile(home.resolve("empty-init.sql"));
        Process process = isolatedProcess(List.of(duckdb.toString(), "-init", init.toString(),
                        "-batch", "-bail", ":memory:"), home)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile())).start();
        BufferedWriter writer = new BufferedWriter(
                new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        QuackServerFixture fixture = new QuackServerFixture(process, port, writer, log, extensions);

        try {
            writer.write("SET extension_directory='" + extensions.toString().replace("'", "''") + "';\n");
            writer.write("SET autoinstall_known_extensions=false;\nSET autoload_known_extensions=false;\n");
            // httpfs supplies the crypto provider needed by Quack's session-ID RNG.
            writer.write("INSTALL httpfs FROM core;\nLOAD httpfs;\n");
            writer.write("INSTALL quack FROM core;\nLOAD quack;\n");
            writer.write("CALL quack_serve('quack:127.0.0.1:" + port + "', token=>'" + DEFAULT_TOKEN + "');\n");
            writer.flush();
            if (!fixture.waitForReady(120_000)) {
                throw new IOException("Quack server exited or did not become ready within 120s; see " + log);
            }
            fixture.verifyIdentity(required);
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
        return builder;
    }

    private void verifyIdentity(boolean required) throws IOException {
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
                validateIdentity(identity, extensionDirectory, required);
                String canonicalPath = "Canonical install path=" + Path.of(identity.installPath()).toRealPath();
                Files.writeString(logFile, canonicalPath + "\n", StandardOpenOption.APPEND);
                System.out.println("Quack fixture " + canonicalPath);
                count++;
            }
            if (count != 2) throw new IOException("Expected loaded Quack and httpfs, found " + count);
        } catch (SQLException e) {
            throw new IOException("Stock Quack identity qualification failed", e);
        }
    }

    record Identity(String runtime, boolean unsigned, boolean unsafeCrypto, String name, boolean loaded,
                    String installPath, String version, String installMode, String installedFrom) {}

    static void validateIdentity(Identity identity, Path extensionDirectory, boolean required) throws IOException {
        if ((required && !DUCKDB_VERSION.equals(identity.runtime())) || identity.unsigned()
                || identity.unsafeCrypto() || !identity.loaded()
                || identity.version() == null || identity.version().isBlank()) {
            throw new IOException("Invalid fixture runtime/signature/extension identity: " + identity);
        }
        if (required && "quack".equals(identity.name())
                && (identity.version().length() < 7 || !QUACK_REVISION.startsWith(identity.version()))) {
            throw new IOException("Unexpected stock Quack source revision: " + identity);
        }
        if (!("quack".equals(identity.name()) || "httpfs".equals(identity.name()))
                || !"REPOSITORY".equals(identity.installMode()) || !"core".equals(identity.installedFrom())
                || identity.installPath() == null || identity.installPath().isBlank()) {
            throw new IOException("Expected isolated official core extension: " + identity);
        }
        Path installed;
        try {
            installed = Path.of(identity.installPath()).toRealPath();
        } catch (InvalidPathException e) {
            throw new IOException("Invalid extension install path: " + identity, e);
        }
        if (!Files.isRegularFile(installed) || !installed.startsWith(extensionDirectory.toRealPath())) {
            throw new IOException("Extension install path escapes isolated directory: " + identity);
        }
    }

    static void validateCliVersion(int exitCode, String output, boolean required) throws IOException {
        if (exitCode != 0 || output == null || output.isBlank()
                || (required && !output.matches("(?s)^" + java.util.regex.Pattern.quote(DUCKDB_VERSION)
                        + "(?:\\s.*)?$"))) {
            throw new IOException("Expected working DuckDB CLI" + (required ? " " + DUCKDB_VERSION : "")
                    + ", got: " + output);
        }
    }

    public String jdbcUrl() {
        return "jdbc:quack://127.0.0.1:" + port + "?token=" + DEFAULT_TOKEN;
    }

    public int port() {
        return port;
    }

    public String token() {
        return DEFAULT_TOKEN;
    }

    public Path logFile() {
        return logFile;
    }

    @Override
    public void close() {
        try {
            stdin.write("CALL quack_stop('quack:127.0.0.1:" + port + "');\n");
            stdin.write(".quit\n");
            stdin.flush();
        } catch (IOException ignored) {
        } finally {
            try { stdin.close(); } catch (IOException ignored) { }
        }
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    // ---- helpers ----

    private static int pickFreePort() throws IOException {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private boolean waitForReady(long timeoutMillis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (process.isAlive() && System.nanoTime() < deadline) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("127.0.0.1", port), 500);
                return true;
            } catch (IOException ignored) {
                Thread.sleep(250);
            }
        }
        return false;
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
        Path direct;
        try {
            direct = Path.of(binary);
        } catch (InvalidPathException e) {
            return null;
        }
        // An absolute path (or any path with a directory component, e.g. ./duckdb)
        // is checked directly; only a bare command name is resolved against PATH.
        List<Path> candidates = new ArrayList<>();
        if (direct.isAbsolute() || direct.getParent() != null) {
            candidates.add(direct);
        } else if (path != null) {
            for (String entry : path.split(java.io.File.pathSeparator, -1)) {
                try { candidates.add(Path.of(entry, binary)); } catch (InvalidPathException ignored) { }
            }
        }
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                try { return candidate.toRealPath(); } catch (IOException ignored) { }
            }
        }
        return null;
    }
}

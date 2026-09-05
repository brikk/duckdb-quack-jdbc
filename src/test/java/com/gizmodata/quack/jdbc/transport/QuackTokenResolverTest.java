package com.gizmodata.quack.jdbc.transport;

import com.gizmodata.quack.jdbc.QuackException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuackTokenResolverTest {

    @TempDir
    Path tempDir;

    @Test
    void explicitTokenPrecedesIndirectTokenSources() throws IOException {
        Path tokenFile = tempDir.resolve("token.txt");
        Files.writeString(tokenFile, "from-file\n", StandardCharsets.UTF_8);
        Properties props = new Properties();
        props.setProperty("token", " \texplicit\r\n");
        props.setProperty("password", " \tfrom-password\r\n");
        props.setProperty("tokenFile", tokenFile.toString());

        QuackUri uri = QuackUri.parse("jdbc:quack://example.test:9494", props);

        assertEquals("explicit", uri.token().orElseThrow());
        assertEquals(props, uri.properties());

        props.setProperty("token", " \t\r\n");
        uri = QuackUri.parse("jdbc:quack://example.test:9494", props);
        assertEquals("from-password", uri.token().orElseThrow());
        assertEquals(props, uri.properties());
    }

    @Test
    void resolvesTokenFromFile() throws IOException {
        Path tokenFile = tempDir.resolve("token.txt");
        Files.writeString(tokenFile, " \tfrom-file\r\n", StandardCharsets.UTF_8);
        Properties props = new Properties();
        props.setProperty("token", " \t");
        props.setProperty("password", "\r\n");
        props.setProperty("tokenEnv", " ");
        props.setProperty("tokenFile", " \t" + tokenFile + "\r\n");

        QuackUri uri = QuackUri.parse("jdbc:quack://example.test:9494", props);

        assertEquals("from-file", uri.token().orElseThrow());
        assertEquals(props, uri.properties());
        assertEquals(Optional.empty(), QuackTokenResolver.resolve(Map.of()));
        assertEquals(Optional.empty(), QuackTokenResolver.resolve(Map.of(
                "token", " ", "password", "\t", "tokenEnv", "\r", "tokenFile", "\n")));
    }

    @Test
    void fileFailureDiagnosticsExcludeSourceInputs() throws IOException {
        Path empty = Files.createFile(tempDir.resolve("B40_SYNTHETIC_PATH-empty"));
        Path whitespace = Files.writeString(tempDir.resolve("B40_SYNTHETIC_PATH-whitespace"), " \t\r\n");
        Path malformed = Files.writeString(tempDir.resolve("B40_SYNTHETIC_PATH-malformed"),
                "B40_SYNTHETIC_FILE_CONTENT", StandardCharsets.UTF_8);
        Files.write(malformed, new byte[]{(byte) 0xc3, 0x28}, StandardOpenOption.APPEND);
        Map<String, String> cases = Map.of(
                empty.toString(), "Quack tokenFile is empty",
                whitespace.toString(), "Quack tokenFile is empty",
                tempDir.resolve("B40_SYNTHETIC_PATH-missing").toString(), "Failed to read Quack tokenFile",
                malformed.toString(), "Failed to read Quack tokenFile",
                tempDir.resolve("B40_SYNTHETIC_PATH-invalid") + "\0suffix", "Failed to read Quack tokenFile");

        assertAll("tokenFile failures", cases.entrySet().stream().map(entry -> () ->
                assertSourceFailure(Map.of("tokenFile", entry.getKey()), entry.getValue(),
                        tempDir.toString(), "B40_SYNTHETIC_PATH", "B40_SYNTHETIC_FILE_CONTENT")));
    }

    @Test
    void environmentSourceMatrixRunsWithControlledEnvironment() throws IOException {
        Path tokenFile = Files.writeString(tempDir.resolve("B40_SYNTHETIC_PATH-fallback"), "from-file\n");
        String envName = "B40_SYNTHETIC_ENV_" + UUID.randomUUID().toString().replace("-", "");
        assertAll("tokenEnv values", Stream.of(null, "", " \t\r\n", " \tB40_SYNTHETIC_ENV_TOKEN\r\n")
                .map(value -> () -> {
                    ProcessBuilder builder = new ProcessBuilder(
                            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                            "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                            QuackTokenResolverTest.class.getName(), envName, tokenFile.toString(),
                            value == null || value.isBlank() ? "failure" : "success");
                    builder.environment().clear();
                    if (value != null) {
                        builder.environment().put(envName, value);
                    }
                    Process child = builder.redirectErrorStream(true).start();
                    try {
                        assertTrue(child.waitFor(20, TimeUnit.SECONDS), "Token environment child JVM timed out");
                        String output = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                        assertEquals(0, child.exitValue(), output);
                    } finally {
                        child.destroyForcibly();
                    }
                }));
    }

    public static void main(String[] args) {
        String envName = args[0];
        Map<String, String> properties = Map.of("tokenEnv", " \t" + envName + "\r\n", "tokenFile", args[1]);
        assertEquals(Optional.of("explicit"), QuackTokenResolver.resolve(Map.of(
                "token", " \texplicit\r\n", "password", "from-password",
                "tokenEnv", envName, "tokenFile", args[1])));
        assertEquals(Optional.of("from-password"), QuackTokenResolver.resolve(Map.of(
                "token", " \t", "password", " \tfrom-password\r\n",
                "tokenEnv", envName, "tokenFile", args[1])));
        if (args[2].equals("failure")) {
            // A configured but empty/missing environment source must not fall back to the file.
            assertSourceFailure(properties, "Quack tokenEnv environment variable is unset or empty",
                    envName, args[1], "B40_SYNTHETIC_ENV_TOKEN");
        } else {
            Properties props = new Properties();
            props.putAll(properties);
            QuackUri uri = QuackUri.parse("jdbc:quack://example.test:9494", props);
            assertEquals("B40_SYNTHETIC_ENV_TOKEN", uri.token().orElseThrow());
            assertEquals(props, uri.properties());
        }
    }

    private static void assertSourceFailure(Map<String, String> properties, String expectedMessage,
                                            String... sourceInputs) {
        QuackException failure = assertThrows(QuackException.class, () -> QuackTokenResolver.resolve(properties));
        StringWriter trace = new StringWriter();
        failure.printStackTrace(new PrintWriter(trace));
        assertAll(
                () -> assertEquals(expectedMessage, failure.getMessage()),
                () -> assertNull(failure.getCause(), "Source exceptions must not be retained"),
                () -> assertEquals(0, failure.getSuppressed().length),
                () -> {
                    for (String input : sourceInputs) {
                        assertFalse(trace.toString().contains(input), "Stack trace contains a source input");
                    }
                });
    }
}

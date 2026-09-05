package com.gizmodata.quack.jdbc.transport;

import com.gizmodata.quack.jdbc.QuackException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

final class QuackTokenResolver {

    static final String TOKEN = "token";
    static final String PASSWORD = "password";
    static final String TOKEN_ENV = "tokenEnv";
    static final String TOKEN_FILE = "tokenFile";

    private QuackTokenResolver() {
    }

    static Optional<String> resolve(Map<String, String> properties) {
        Optional<String> token = nonBlank(properties.get(TOKEN));
        if (token.isPresent()) {
            return token;
        }
        token = nonBlank(properties.get(PASSWORD));
        if (token.isPresent()) {
            return token;
        }
        token = resolveFromEnv(properties.get(TOKEN_ENV));
        if (token.isPresent()) {
            return token;
        }
        token = resolveFromFile(properties.get(TOKEN_FILE));
        if (token.isPresent()) {
            return token;
        }
        return Optional.empty();
    }

    private static Optional<String> resolveFromEnv(String envName) {
        Optional<String> name = nonBlank(envName);
        if (name.isEmpty()) {
            return Optional.empty();
        }
        String value;
        try {
            value = System.getenv(name.get());
        } catch (SecurityException ignored) {
            // Source-access exceptions can contain secret names or paths; do not retain them.
            throw new QuackException("Failed to read Quack tokenEnv environment variable");
        }
        return Optional.of(nonBlank(value).orElseThrow(
                () -> new QuackException("Quack tokenEnv environment variable is unset or empty")));
    }

    private static Optional<String> resolveFromFile(String tokenFile) {
        Optional<String> file = nonBlank(tokenFile);
        if (file.isEmpty()) {
            return Optional.empty();
        }
        String value;
        try {
            value = Files.readString(Path.of(file.get()), StandardCharsets.UTF_8);
        } catch (IOException | InvalidPathException | SecurityException ignored) {
            throw new QuackException("Failed to read Quack tokenFile");
        }
        return Optional.of(nonBlank(value).orElseThrow(
                () -> new QuackException("Quack tokenFile is empty")));
    }

    private static Optional<String> nonBlank(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(value.trim());
    }
}

package com.gizmodata.quack.jdbc.it;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
class QuackServerFixtureTest {
    @TempDir Path temp;

    @Test
    void missingCliIsOptionalLocallyButCannotDisableRequiredIntegration() throws Exception {
        String missing = temp.resolve("missing-duckdb").toString();
        assertFalse(QuackServerFixture.enabled(false, missing, null));
        assertNull(QuackServerFixture.requireExecutable(missing, null, false));
        assertTrue(QuackServerFixture.enabled(true, missing, null));
        IOException failure = assertThrows(IOException.class,
                () -> QuackServerFixture.requireExecutable(missing, null, true));
        assertTrue(failure.getMessage().contains("QUACK_IT_DUCKDB=" + missing));
        assertNull(QuackServerFixture.resolveExecutable("duckdb", null));
        assertNull(QuackServerFixture.resolveExecutable("", temp.toString()));
        assertNull(QuackServerFixture.resolveExecutable("bad\0path", null));
    }

    @Test
    void requiredPropertyEnablesTheIntegrationClassCondition() {
        String previous = System.getProperty("quack.it.required");
        try {
            System.setProperty("quack.it.required", "true");
            assertTrue(QuackServerFixture.required());
            assertTrue(QuackIntegrationTest.duckdbAvailable());
            System.setProperty("quack.it.required", "false");
            assertFalse(QuackServerFixture.required());
            assertEquals(QuackServerFixture.enabled(false,
                            System.getenv().getOrDefault("QUACK_IT_DUCKDB", "duckdb"), System.getenv("PATH")),
                    QuackIntegrationTest.duckdbAvailable());
        } finally {
            if (previous == null) System.clearProperty("quack.it.required");
            else System.setProperty("quack.it.required", previous);
        }
    }

    @Test
    void executableMustBeARegularFileAndIsResolvedToItsRealPath() throws Exception {
        assertNull(QuackServerFixture.resolveExecutable(temp.toString(), null));
        Path binary = Files.createFile(temp.resolve("duckdb"));
        assertTrue(binary.toFile().setExecutable(false, false));
        assertNull(QuackServerFixture.resolveExecutable(binary.toString(), null));
        assertThrows(IOException.class,
                () -> QuackServerFixture.requireExecutable(binary.toString(), null, true));
        assertTrue(binary.toFile().setExecutable(true, true));
        assertEquals(binary.toRealPath(), QuackServerFixture.resolveExecutable(binary.toString(), null));
        assertEquals(binary.toRealPath(), QuackServerFixture.resolveExecutable("duckdb", temp.toString()));
        Path relative = Path.of("").toAbsolutePath().relativize(binary.toAbsolutePath());
        assertEquals(binary.toRealPath(), QuackServerFixture.resolveExecutable(relative.toString(), null));
        Path alias = Files.createSymbolicLink(temp.resolve("duckdb-link"), binary);
        assertEquals(binary.toRealPath(), QuackServerFixture.resolveExecutable(alias.toString(), null));
    }

    @Test
    void cliPinChecksActualOutputAndExitStatusOnlyWhenRequired() throws Exception {
        QuackServerFixture.validateCliVersion(0, "v1.5.6 (Variegata) 069cc9f9b5", true);
        for (String wrong : new String[]{"v1.5.5", "v1.5.60", "wrapper says v1.5.6", "", "v1.5.6-dev"}) {
            assertThrows(IOException.class, () -> QuackServerFixture.validateCliVersion(0, wrong, true));
        }
        QuackServerFixture.validateCliVersion(0, "v1.5.4 (Variegata)", false);
        for (boolean required : new boolean[]{false, true}) {
            assertThrows(IOException.class, () -> QuackServerFixture.validateCliVersion(1, "v1.5.6", required));
            assertThrows(IOException.class, () -> QuackServerFixture.validateCliVersion(0, "", required));
        }
    }

    @Test
    void requiredOracleCannotTurnIntoAnAssumptionSkip() {
        ClassLoader missingOracle = new ClassLoader(null) {};
        assertFalse(DuckDbOracle.available(false, missingOracle));
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> DuckDbOracle.available(true, missingOracle));
        assertTrue(failure.getMessage().contains("-Poracle"));
        assertNotNull(failure.getCause());
    }

    @Test
    void requiredStockIdentityPinsRuntimeAndQuackRevision() throws Exception {
        Path installed = Files.createFile(temp.resolve("quack.duckdb_extension"));
        QuackServerFixture.validateIdentity(new QuackServerFixture.Identity("v1.5.6", false, false,
                "quack", true, installed.toString(), "7e80f7f", "REPOSITORY", "core"), temp, true);
        for (var wrong : new QuackServerFixture.Identity[]{
                new QuackServerFixture.Identity("v1.5.5", false, false, "quack", true,
                        installed.toString(), "7e80f7f", "REPOSITORY", "core"),
                new QuackServerFixture.Identity("v1.5.6", false, false, "quack", true,
                        installed.toString(), "abcdef0", "REPOSITORY", "core"),
                new QuackServerFixture.Identity("v1.5.6", false, false, "quack", true,
                        installed.toString(), "7e80f7", "REPOSITORY", "core"),
                new QuackServerFixture.Identity("v1.5.6", false, false, "quack", true,
                        installed.toString(), "c154811", "REPOSITORY", "core")}) {
            assertThrows(IOException.class, () -> QuackServerFixture.validateIdentity(wrong, temp, true));
            QuackServerFixture.validateIdentity(wrong, temp, false);
        }
        QuackServerFixture.validateIdentity(new QuackServerFixture.Identity("v1.5.6", false, false,
                "httpfs", true, installed.toString(), "other-revision", "REPOSITORY", "core"), temp, true);
    }

    @Test
    void stockIdentityRequiresLoadedSignedCoreExtensionsAndSafeCrypto() throws Exception {
        Path installed = Files.createFile(temp.resolve("quack.duckdb_extension"));
        for (var wrong : new QuackServerFixture.Identity[]{
                new QuackServerFixture.Identity("v1.5.6", true, false, "quack", true,
                        installed.toString(), "7e80f7f", "REPOSITORY", "core"),
                new QuackServerFixture.Identity("v1.5.6", false, true, "quack", true,
                        installed.toString(), "7e80f7f", "REPOSITORY", "core"),
                new QuackServerFixture.Identity("v1.5.6", false, false, "quack", false,
                        installed.toString(), "7e80f7f", "REPOSITORY", "core"),
                new QuackServerFixture.Identity("v1.5.6", false, false, "other", true,
                        installed.toString(), "7e80f7f", "REPOSITORY", "core"),
                new QuackServerFixture.Identity("v1.5.6", false, false, "quack", true,
                        installed.toString(), "7e80f7f", "REPOSITORY", "community"),
                new QuackServerFixture.Identity("v1.5.6", false, false, "quack", true,
                        installed.toString(), "7e80f7f", "NOT_INSTALLED", "core"),
                new QuackServerFixture.Identity("v1.5.6", false, false, "quack", true,
                        installed.toString(), "", "REPOSITORY", "core")}) {
            for (boolean required : new boolean[]{false, true}) {
                assertThrows(IOException.class, () -> QuackServerFixture.validateIdentity(wrong, temp, required));
            }
        }
    }

    @Test
    void stockInstallPathMustStayInsideCanonicalIsolatedDirectory() throws Exception {
        Path extensions = Files.createDirectory(temp.resolve("extensions"));
        Path installed = Files.createFile(extensions.resolve("quack.duckdb_extension"));
        Path alias = Files.createSymbolicLink(extensions.resolve("quack-link"), installed);
        QuackServerFixture.validateIdentity(new QuackServerFixture.Identity("v1.5.6", false, false,
                "quack", true, alias.toString(), "7e80f7f", "REPOSITORY", "core"), extensions, true);
        Path cached = Files.createFile(temp.resolve("cached-extension"));
        Path symlink = Files.createSymbolicLink(extensions.resolve("cache-link"), cached);
        for (String wrong : new String[]{null, "", "bad\0path", extensions.toString(),
                extensions.resolve("missing").toString(), cached.toString(), symlink.toString(),
                extensions.resolve("../cached-extension").toString()}) {
            for (boolean required : new boolean[]{false, true}) {
                assertThrows(IOException.class, () -> QuackServerFixture.validateIdentity(
                        new QuackServerFixture.Identity("v1.5.6", false, false, "quack", true,
                                wrong, "7e80f7f", "REPOSITORY", "core"), extensions, required));
            }
        }
    }
}

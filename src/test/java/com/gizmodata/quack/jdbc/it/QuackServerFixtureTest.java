package com.gizmodata.quack.jdbc.it;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuackServerFixtureTest {
    @TempDir Path temp;

    @Test
    void missingCliIsOptionalLocallyButCannotDisableRequiredIntegration() throws Exception {
        String missing = temp.resolve("missing-duckdb").toString();
        assertFalse(QuackServerFixture.enabled(false, missing, null));
        assertNull(QuackServerFixture.requireExecutable(missing, null, false));
        assertTrue(QuackServerFixture.enabled(true, missing, null));
        assertThrows(IOException.class, () -> QuackServerFixture.requireExecutable(missing, null, true));
        assertNull(QuackServerFixture.resolveExecutable("duckdb", null));
        assertNull(QuackServerFixture.resolveExecutable("", temp.toString()));
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
        Path alias = Files.createSymbolicLink(temp.resolve("duckdb-link"), binary);
        assertEquals(binary.toRealPath(), QuackServerFixture.resolveExecutable(alias.toString(), null));
    }

    @Test
    void defaultRequiredProfileNeedsAnArtifactButLegacyAlwaysIgnoresIt() throws Exception {
        assertNull(QuackServerFixture.resultMetadataArtifact(false, null, false));
        assertThrows(IOException.class, () -> QuackServerFixture.resultMetadataArtifact(false, null, true));
        assertThrows(IOException.class, () -> QuackServerFixture.resultMetadataArtifact(false, " ", true));
        assertThrows(IOException.class,
                () -> QuackServerFixture.resultMetadataArtifact(false, temp.resolve("missing").toString(), false));
        assertThrows(IOException.class,
                () -> QuackServerFixture.resultMetadataArtifact(false, temp.toString(), true));
        assertNull(QuackServerFixture.resultMetadataArtifact(true, "missing", true));
        Path artifact = Files.createFile(temp.resolve("quack.duckdb_extension"));
        assertEquals(artifact.toRealPath(),
                QuackServerFixture.resultMetadataArtifact(false, artifact.toString(), true));
    }

    @Test
    void cliPinChecksActualOutputAndExitStatus() throws Exception {
        QuackServerFixture.validateCliVersion(0, "v1.5.5 (Variegata) d8cdaa33fd");
        for (String wrong : new String[]{"v1.5.4", "v1.5.50", "wrapper says v1.5.5", "", "v1.5.5-dev"}) {
            assertThrows(IOException.class, () -> QuackServerFixture.validateCliVersion(0, wrong));
        }
        assertThrows(IOException.class, () -> QuackServerFixture.validateCliVersion(1, "v1.5.5"));
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
    void patchedIdentityRequiresPinnedRuntimeLoadedExtensionAndExplicitUnsignedMode() throws Exception {
        Path artifact = Files.createFile(temp.resolve("quack.duckdb_extension"));
        var good = new QuackServerFixture.Identity("v1.5.5", true, false, "quack", true,
                "", "c154811", "NOT_INSTALLED", "");
        QuackServerFixture.validateIdentity(good, artifact, temp);
        for (var wrong : new QuackServerFixture.Identity[]{
                new QuackServerFixture.Identity("v1.5.4", true, false, "quack", true, "", "c154811", "NOT_INSTALLED", ""),
                new QuackServerFixture.Identity("v1.5.5", false, false, "quack", true, "", "c154811", "NOT_INSTALLED", ""),
                new QuackServerFixture.Identity("v1.5.5", true, true, "quack", true, "", "c154811", "NOT_INSTALLED", ""),
                new QuackServerFixture.Identity("v1.5.5", true, false, "quack", false, "", "c154811", "NOT_INSTALLED", ""),
                new QuackServerFixture.Identity("v1.5.5", true, false, "quack", true, "", "abcdef0", "NOT_INSTALLED", ""),
                new QuackServerFixture.Identity("v1.5.5", true, false, "quack", true, "", "", "NOT_INSTALLED", "")}) {
            assertThrows(IOException.class, () -> QuackServerFixture.validateIdentity(wrong, artifact, temp));
        }
        Path unrelated = Files.createFile(temp.resolve("cached-quack.duckdb_extension"));
        var wrongPath = new QuackServerFixture.Identity("v1.5.5", true, false, "quack", true,
                unrelated.toString(), "c154811", "REPOSITORY", "core");
        assertThrows(IOException.class, () -> QuackServerFixture.validateIdentity(wrongPath, artifact, temp));
        Path alias = Files.createSymbolicLink(temp.resolve("artifact-link"), artifact);
        QuackServerFixture.validateIdentity(new QuackServerFixture.Identity("v1.5.5", true, false, "quack", true,
                alias.toString(), "c154811", "NOT_INSTALLED", ""), artifact, temp);
    }

    @Test
    void stockIdentityRequiresSignedCoreInstallInsideIsolatedDirectory() throws Exception {
        Path extensions = Files.createDirectory(temp.resolve("extensions"));
        Path installed = Files.createFile(extensions.resolve("quack.duckdb_extension"));
        QuackServerFixture.validateIdentity(new QuackServerFixture.Identity("v1.5.5", false, false,
                "quack", true, installed.toString(), "c154811", "REPOSITORY", "core"), null, extensions);
        for (var wrong : new QuackServerFixture.Identity[]{
                new QuackServerFixture.Identity("v1.5.5", true, false, "quack", true,
                        installed.toString(), "c154811", "REPOSITORY", "core"),
                new QuackServerFixture.Identity("v1.5.5", false, false, "quack", true,
                        installed.toString(), "c154811", "REPOSITORY", "community"),
                new QuackServerFixture.Identity("v1.5.5", false, false, "quack", true,
                        "", "c154811", "NOT_INSTALLED", "")}) {
            assertThrows(IOException.class, () -> QuackServerFixture.validateIdentity(wrong, null, extensions));
        }
        Path cached = Files.createFile(temp.resolve("global-cache-extension"));
        Path symlink = Files.createSymbolicLink(extensions.resolve("cache-link"), cached);
        assertThrows(IOException.class, () -> QuackServerFixture.validateIdentity(
                new QuackServerFixture.Identity("v1.5.5", false, false, "quack", true,
                        symlink.toString(), "c154811", "REPOSITORY", "core"), null, extensions));
    }
}

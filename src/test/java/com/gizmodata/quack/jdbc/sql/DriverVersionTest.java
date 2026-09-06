package com.gizmodata.quack.jdbc.sql;

import com.gizmodata.quack.jdbc.QuackException;
import com.gizmodata.quack.jdbc.codec.HugeIntParts;
import com.gizmodata.quack.jdbc.message.DataChunk;
import com.gizmodata.quack.jdbc.message.DecodedVector;
import com.gizmodata.quack.jdbc.message.MessageHeader;
import com.gizmodata.quack.jdbc.message.MessageType;
import com.gizmodata.quack.jdbc.message.QuackMessage;
import com.gizmodata.quack.jdbc.transport.QuackTransport;
import com.gizmodata.quack.jdbc.type.LogicalType;
import com.gizmodata.quack.jdbc.type.LogicalTypeId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EmptySource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DriverVersionTest {

    @Test
    void generatedVersionMatchesIndependentMavenProperty() {
        String expected = expectedVersion();
        String[] components = expected.split("\\.", 3);
        assertEquals(expected, DriverVersion.VERSION);
        assertEquals(Integer.parseInt(components[0]), DriverVersion.MAJOR_VERSION);
        assertEquals(Integer.parseInt(components[1]), DriverVersion.MINOR_VERSION);
        assertEquals("quack-jdbc/" + expected, DriverVersion.CLIENT_VERSION);
    }

    @Test
    void driverConstantsRemainUsableAsCompileTimeConstants() {
        QuackDriver driver = new QuackDriver();
        assertEquals(DriverVersion.MAJOR_VERSION, QuackDriver.MAJOR_VERSION);
        assertEquals(DriverVersion.MINOR_VERSION, QuackDriver.MINOR_VERSION);
        // Case labels fail compilation if either public field stops being a constant expression.
        assertTrue(switch (driver.getMajorVersion()) {
            case QuackDriver.MAJOR_VERSION -> true;
            default -> false;
        });
        assertTrue(switch (driver.getMinorVersion()) {
            case QuackDriver.MINOR_VERSION -> true;
            default -> false;
        });
    }

    @Test
    void metadataAndHandshakeShareTheFullDriverIdentity() throws SQLException {
        VersionTransport transport = new VersionTransport("v1.5.5");
        try (QuackConnection connection = connect(transport)) {
            QuackDatabaseMetaData metadata = assertInstanceOf(QuackDatabaseMetaData.class, connection.getMetaData());
            String[] components = expectedVersion().split("\\.", 3);
            assertEquals("quack-jdbc", metadata.getDriverName());
            assertEquals(expectedVersion(), metadata.getDriverVersion());
            assertEquals(Integer.parseInt(components[0]), metadata.getDriverMajorVersion());
            assertEquals(Integer.parseInt(components[1]), metadata.getDriverMinorVersion());
            QuackMessage.ConnectionRequest request = assertInstanceOf(
                    QuackMessage.ConnectionRequest.class, transport.requests.get(0));
            assertEquals(Optional.of("quack-jdbc/" + expectedVersion()), request.clientDuckdbVersion());
            assertEquals(1, transport.requests.size());
        }
    }

    @ParameterizedTest
    @CsvSource({
            "v1.5.5, 1, 5",
            "1.5.5, 1, 5",
            "v1.5.6-dev146, 1, 5",
            "v23.17.0-rc.2+build.19, 23, 17",
            "2.10-SNAPSHOT, 2, 10",
            "3.12+build19, 3, 12",
            "0.0.0, 0, 0",
            "7.8, 7, 8"
    })
    void metadataParsesCachedServerVersionWithoutQueries(String version, int major, int minor) throws SQLException {
        VersionTransport transport = new VersionTransport(version);
        try (QuackConnection connection = connect(transport)) {
            connection.setAutoCommit(false);
            QuackDatabaseMetaData metadata = assertInstanceOf(QuackDatabaseMetaData.class, connection.getMetaData());
            assertEquals(Optional.of(version), connection.session().serverDuckdbVersion());
            for (int i = 0; i < 2; i++) {
                assertEquals(version, metadata.getDatabaseProductVersion());
                assertNumericVersion(metadata, major, minor);
            }
            assertEquals(1, transport.requests.size(), "identity must not run SQL or begin a transaction");
        }
    }

    @ParameterizedTest
    @EmptySource
    @ValueSource(strings = {"unknown", " ", "v", "1", "v1.x.5", "1.5oops", "-1.5.5", "1.-5.5",
            "DuckDB v1.5.5", "2147483648.2147483648.0"})
    void unparseableHandshakeVersionIsPreservedAndNumericVersionIsZero(String version) throws SQLException {
        VersionTransport transport = new VersionTransport(version);
        try (QuackConnection connection = connect(transport)) {
            QuackDatabaseMetaData metadata = assertInstanceOf(QuackDatabaseMetaData.class, connection.getMetaData());
            assertEquals(version, metadata.getDatabaseProductVersion());
            assertNumericVersion(metadata, 0, 0);
            assertEquals(1, transport.requests.size(), "a present handshake value must not trigger fallback SQL");
        }
    }

    @Test
    void absentHandshakeVersionRetainsPragmaFallback() throws SQLException {
        VersionTransport transport = new VersionTransport(null);
        transport.pragmaVersion = "v12.34.5-dev146";
        try (QuackConnection connection = connect(transport)) {
            QuackDatabaseMetaData metadata = assertInstanceOf(QuackDatabaseMetaData.class, connection.getMetaData());
            assertEquals(Optional.empty(), connection.session().serverDuckdbVersion());
            assertEquals(transport.pragmaVersion, metadata.getDatabaseProductVersion());
            assertNumericVersion(metadata, 12, 34);
            assertEquals(4, transport.requests.size());
        }
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"unknown", "v1.x.5"})
    void unknownPragmaVersionHasZeroNumericComponents(String version) throws SQLException {
        VersionTransport transport = new VersionTransport(null);
        transport.pragmaVersion = version;
        try (QuackConnection connection = connect(transport)) {
            QuackDatabaseMetaData metadata = assertInstanceOf(QuackDatabaseMetaData.class, connection.getMetaData());
            assertEquals(version, metadata.getDatabaseProductVersion());
            assertNumericVersion(metadata, 0, 0);
        }
    }

    @Test
    void emptyPragmaResultPreservesEmptyProductVersion() throws SQLException {
        VersionTransport transport = new VersionTransport(null);
        transport.emptyPragma = true;
        try (QuackConnection connection = connect(transport)) {
            QuackDatabaseMetaData metadata = assertInstanceOf(QuackDatabaseMetaData.class, connection.getMetaData());
            assertEquals("", metadata.getDatabaseProductVersion());
            assertNumericVersion(metadata, 0, 0);
        }
    }

    @Test
    void unavailablePragmaKeepsProductVersionExceptionButNumericVersionIsZero() throws SQLException {
        VersionTransport transport = new VersionTransport(null);
        transport.failPragma = true;
        try (QuackConnection connection = connect(transport)) {
            QuackDatabaseMetaData metadata = assertInstanceOf(QuackDatabaseMetaData.class, connection.getMetaData());
            assertThrows(SQLException.class, metadata::getDatabaseProductVersion);
            assertNumericVersion(metadata, 0, 0);
        }
    }

    private static String expectedVersion() {
        String expected = System.getProperty("quack.test.expectedDriverVersion");
        assertNotNull(expected, "Maven must supply the expected project version independently of generated code");
        assertFalse(expected.isBlank());
        assertFalse(expected.contains("${"), "Maven must resolve the expected project version");
        return expected;
    }

    // Intentionally no throws clause: preserve the concrete metadata methods' source compatibility.
    private static void assertNumericVersion(QuackDatabaseMetaData metadata, int major, int minor) {
        assertEquals(major, metadata.getDatabaseMajorVersion());
        assertEquals(minor, metadata.getDatabaseMinorVersion());
    }

    private static QuackConnection connect(VersionTransport transport) throws SQLException {
        return assertInstanceOf(QuackConnection.class, new QuackDriver().connect(
                "jdbc:quack://example.test:9494", new Properties(), uri -> transport));
    }

    private static final class VersionTransport implements QuackTransport {
        private final Optional<String> serverVersion;
        private final List<QuackMessage> requests = new ArrayList<>();
        private String pragmaVersion;
        private boolean failPragma;
        private boolean emptyPragma;

        private VersionTransport(String serverVersion) {
            this.serverVersion = Optional.ofNullable(serverVersion);
        }

        @Override
        public QuackMessage send(QuackMessage request) {
            requests.add(request);
            long queryId = request.header().clientQueryId().orElse(0L);
            if (request instanceof QuackMessage.ConnectionRequest) {
                return new QuackMessage.ConnectionResponse(
                        MessageHeader.of(MessageType.CONNECTION_RESPONSE)
                                .withConnectionId("version-test").withClientQueryId(queryId),
                        serverVersion, Optional.empty(), Optional.empty());
            }
            if (request instanceof QuackMessage.PrepareRequest prepare) {
                assertEquals("PRAGMA version", prepare.sql());
                if (failPragma) throw new QuackException("version unavailable");
                LogicalType type = LogicalType.of(LogicalTypeId.VARCHAR);
                List<DataChunk> chunks = emptyPragma ? List.of() : List.of(new DataChunk(1, List.of(type),
                        List.of(new DecodedVector.ObjectVec(type, new Object[]{pragmaVersion}))));
                return new QuackMessage.PrepareResponse(
                        MessageHeader.of(MessageType.PREPARE_RESPONSE)
                                .withConnectionId("version-test").withClientQueryId(queryId),
                        List.of(type), List.of("library_version"), false, chunks, new HugeIntParts(0, 0));
            }
            if (request instanceof QuackMessage.DisconnectMessage) {
                return new QuackMessage.SuccessResponse(MessageHeader.of(MessageType.SUCCESS_RESPONSE)
                        .withConnectionId("version-test").withClientQueryId(queryId));
            }
            throw new AssertionError("Unexpected request: " + request);
        }
    }
}

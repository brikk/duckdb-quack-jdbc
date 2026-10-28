package com.gizmodata.quack.jdbc.sql;

import com.gizmodata.quack.jdbc.QuackException;
import com.gizmodata.quack.jdbc.QuackServerException;
import com.gizmodata.quack.jdbc.message.DataChunk;
import com.gizmodata.quack.jdbc.message.DecodedVector;
import com.gizmodata.quack.jdbc.message.MessageHeader;
import com.gizmodata.quack.jdbc.message.MessageType;
import com.gizmodata.quack.jdbc.message.QuackMessage;
import com.gizmodata.quack.jdbc.transport.QuackTransport;
import com.gizmodata.quack.jdbc.transport.QuackUri;
import com.gizmodata.quack.jdbc.type.LogicalType;
import com.gizmodata.quack.jdbc.type.LogicalTypeId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class QuackSessionInvalidationTest {
    private static final QuackUri URI = QuackUri.parse("jdbc:quack://example.test");
    private static final LogicalType INTEGER = LogicalType.of(LogicalTypeId.INTEGER);
    private static final DataChunk CHUNK = new DataChunk(1, List.of(INTEGER),
            List.of(new DecodedVector.IntVec(INTEGER, new int[]{42}, null)));

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void fatalPrepareInvalidatesJdbcAndStopsAllFurtherTraffic(boolean throwsError) throws Exception {
        ScriptedTransport server = new ScriptedTransport(3, throwsError);
        QuackConnection connection = new QuackConnection(URI, uri -> server);
        try (connection;
             var statement = connection.createStatement();
             var prepared = connection.prepareStatement("SELECT ?")) {
            prepared.setInt(1, 42);
            server.fail(MessageType.PREPARE_REQUEST, true);
            SQLException error = assertThrows(SQLException.class, () -> statement.execute("SELECT 42"));
            assertFatal(assertInstanceOf(QuackServerException.class, error.getCause()));
            assertTrue(connection.session().isInvalidated());
            assertFalse(connection.isClosed(), "invalidation is not an explicit close");
            int requests = server.requests.size();

            assertThrows(SQLException.class, () -> statement.execute("SELECT 43"));
            assertThrows(SQLException.class, prepared::execute);
            assertThrows(QuackException.class, () -> connection.session().cursor("SELECT 44"));
            assertThrows(QuackException.class, () -> connection.session().appendChunk("main", "t", CHUNK));
            SQLNonTransientConnectionException unusable = assertThrows(SQLNonTransientConnectionException.class,
                    connection::createStatement);
            assertEquals("08006", unusable.getSQLState());
            assertTrue(unusable.getMessage().contains("server is restarted"));
            assertThrows(SQLException.class, connection::commit);
            assertThrows(SQLException.class, connection::rollback);
            assertFalse(connection.isValid(1));
            connection.session().sendHeartbeat();
            assertEquals(requests, server.requests.size(), "no probes, heartbeats, reconnects or retries");
        }
        int requests = server.requests.size();
        connection.close();
        assertTrue(connection.isClosed());
        assertTrue(connection.session().isClosed());
        assertEquals(requests, server.requests.size());
        assertFalse(server.requests.contains(MessageType.DISCONNECT_MESSAGE));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void ordinaryErrorsDoNotInvalidateEvenWhenTheirTypeSoundsFatal(boolean throwsError) throws Exception {
        ScriptedTransport server = new ScriptedTransport(3, throwsError);
        try (var connection = new QuackConnection(URI, uri -> server);
             var statement = connection.createStatement()) {
            server.fail(MessageType.PREPARE_REQUEST, false);
            SQLException error = assertThrows(SQLException.class, () -> statement.execute("SELECT broken"));
            var cause = assertInstanceOf(QuackServerException.class, error.getCause());
            assertEquals("Fatal", cause.getExceptionType());
            assertFalse(cause.mustInvalidate());
            assertFalse(connection.session().isInvalidated());
            assertTrue(statement.execute("SELECT 42"));
            assertTrue(connection.isValid(1));
        }
        assertEquals(MessageType.DISCONNECT_MESSAGE, server.requests.get(server.requests.size() - 1));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void fatalFetchInvalidatesTheOwningConnection(boolean throwsError) throws Exception {
        ScriptedTransport server = new ScriptedTransport(3, throwsError);
        try (var connection = new QuackConnection(URI, uri -> server);
             var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT streaming")) {
            server.fail(MessageType.FETCH_REQUEST, true);
            SQLException error = assertThrows(SQLException.class, result::next);
            assertFatal(assertInstanceOf(QuackServerException.class, error.getCause()));
            assertTrue(connection.session().isInvalidated());
            int requests = server.requests.size();
            assertFalse(result.next());
            assertThrows(SQLException.class, () -> statement.execute("SELECT 42"));
            assertFalse(connection.isValid(1));
            assertEquals(requests, server.requests.size());
        }
        assertFalse(server.requests.contains(MessageType.DISCONNECT_MESSAGE));
    }

    @ParameterizedTest
    @CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void fatalSendDataStopsTheAppendBeforeAnyMoreBatchesOrFetches(boolean throwsError, boolean terminal) {
        ScriptedTransport server = new ScriptedTransport(3, throwsError);
        try (var session = QuackSession.connect(URI, server)) {
            server.fail(MessageType.SEND_DATA_REQUEST, true);
            server.skipFailures = terminal ? 1 : 0;
            assertFatal(assertThrows(QuackServerException.class, () -> session.appendChunk("main", "t", CHUNK)));
            assertTrue(session.isInvalidated());
            assertEquals(terminal ? 2 : 1,
                    server.requests.stream().filter(type -> type == MessageType.SEND_DATA_REQUEST).count());
            assertFalse(server.requests.contains(MessageType.FETCH_REQUEST));
            int requests = server.requests.size();
            assertThrows(QuackException.class, () -> session.appendChunk("main", "t", CHUNK));
            assertThrows(QuackException.class, () -> session.cursor("SELECT 42"));
            session.sendHeartbeat();
            assertEquals(requests, server.requests.size());
        }
        assertFalse(server.requests.contains(MessageType.DISCONNECT_MESSAGE));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void fatalErrorOnTheLegacyAppendPathAlsoInvalidates(boolean throwsError) {
        ScriptedTransport server = new ScriptedTransport(1, throwsError);
        try (var session = QuackSession.connect(URI, server)) {
            server.fail(MessageType.APPEND_REQUEST, true);
            assertFatal(assertThrows(QuackServerException.class, () -> session.appendChunk("main", "t", CHUNK)));
            assertTrue(session.isInvalidated());
            int requests = server.requests.size();
            assertThrows(QuackException.class, () -> session.cursor("SELECT 42"));
            assertEquals(requests, server.requests.size());
        }
        assertFalse(server.requests.contains(MessageType.DISCONNECT_MESSAGE));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void fatalHeartbeatInvalidatesWithoutWaitingForAForegroundFailure(boolean throwsError) {
        ScriptedTransport server = new ScriptedTransport(3, throwsError);
        try (var session = QuackSession.connect(URI, server)) {
            server.fail(MessageType.HEARTBEAT_REQUEST, true);
            session.sendHeartbeat();
            assertTrue(session.isInvalidated());
            int requests = server.requests.size();
            session.sendHeartbeat();
            QuackException error = assertThrows(QuackException.class, () -> session.cursor("SELECT 42"));
            assertFatal(assertInstanceOf(QuackServerException.class, error.getCause()));
            assertEquals(requests, server.requests.size());
        }
        assertFalse(server.requests.contains(MessageType.DISCONNECT_MESSAGE));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void fatalHandshakeNeverRetriesEvenIfItsTextMatchesTheV3LeaseProbe(boolean throwsError) {
        ScriptedTransport server = new ScriptedTransport(3, throwsError);
        server.fail(MessageType.CONNECTION_REQUEST, true);
        server.errorMessage = "heartbeat_timeout out of range";
        var error = assertThrows(QuackServerException.class, () -> QuackSession.connect(URI, server));
        assertTrue(error.mustInvalidate());
        assertEquals(List.of(MessageType.CONNECTION_REQUEST), server.requests);
    }

    @Test
    void legacyServerExceptionsDefaultToRecoverableAndCopyTheirMetadata() {
        var legacy = new QuackServerException("legacy failure");
        assertFalse(legacy.mustInvalidate());
        assertEquals("", legacy.getExceptionType());
        assertEquals(Map.of(), legacy.getExtraInfo());
        Map<String, String> info = new java.util.HashMap<>(Map.of("detail", "original"));
        var error = new QuackServerException("error", "IO", info, true);
        info.clear();
        assertEquals(Map.of("detail", "original"), error.getExtraInfo());
        assertThrows(UnsupportedOperationException.class, () -> error.getExtraInfo().clear());
    }

    private static void assertFatal(QuackServerException error) {
        assertEquals("database failed", error.getServerMessage());
        assertEquals("Fatal", error.getExceptionType());
        assertEquals(Map.of("detail", "checkpoint"), error.getExtraInfo());
        assertTrue(error.mustInvalidate());
    }

    private static final class ScriptedTransport implements QuackTransport {
        final List<MessageType> requests = new ArrayList<>();
        final long version;
        final boolean throwsError;
        MessageType failType;
        boolean mustInvalidate;
        int skipFailures;
        String errorMessage = "database failed";

        ScriptedTransport(long version, boolean throwsError) {
            this.version = version;
            this.throwsError = throwsError;
        }

        void fail(MessageType type, boolean invalidates) {
            failType = type;
            mustInvalidate = invalidates;
        }

        @Override
        public QuackMessage send(QuackMessage request) {
            MessageType type = request.header().type();
            requests.add(type);
            if (type == failType && skipFailures-- <= 0) {
                failType = null;
                Map<String, String> info = Map.of("detail", "checkpoint");
                if (throwsError) throw new QuackServerException(errorMessage, "Fatal", info, mustInvalidate);
                return new QuackMessage.ErrorResponse(MessageHeader.of(MessageType.ERROR_RESPONSE),
                        errorMessage, "Fatal", info, mustInvalidate);
            }
            if (request instanceof QuackMessage.ConnectionRequest) {
                return new QuackMessage.ConnectionResponse(
                        MessageHeader.of(MessageType.CONNECTION_RESPONSE).withConnectionId("test-session"),
                        Optional.empty(), Optional.empty(), Optional.of(version),
                        version == 3 ? Optional.of(60L) : Optional.empty());
            }
            if (request instanceof QuackMessage.PrepareRequest prepare) {
                String sql = prepare.sql();
                boolean insert = sql.startsWith("INSERT INTO");
                boolean streaming = sql.equals("SELECT streaming") || insert;
                DataChunk rows = sql.equals("SELECT 1") ? new DataChunk(1, List.of(INTEGER),
                        List.of(new DecodedVector.IntVec(INTEGER, new int[]{1}, null))) : CHUNK;
                return new QuackMessage.PrepareResponse(MessageHeader.of(MessageType.PREPARE_RESPONSE),
                        List.of(INTEGER), List.of(insert ? "Count" : "value"), streaming,
                        streaming || sql.startsWith("SELECT * FROM") ? List.of() : List.of(rows),
                        prepare.queryUuid());
            }
            if (request instanceof QuackMessage.FetchRequest fetch) {
                return new QuackMessage.FetchResponse(MessageHeader.of(MessageType.FETCH_RESPONSE),
                        List.of(), Optional.empty(), Optional.of(0L));
            }
            if (request instanceof QuackMessage.SendDataRequest) {
                return new QuackMessage.SendDataResponse(MessageHeader.of(MessageType.SEND_DATA_RESPONSE),
                        Optional.empty());
            }
            return new QuackMessage.SuccessResponse(MessageHeader.of(MessageType.SUCCESS_RESPONSE));
        }
    }
}

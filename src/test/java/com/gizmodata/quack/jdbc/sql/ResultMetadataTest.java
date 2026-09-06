package com.gizmodata.quack.jdbc.sql;

import com.gizmodata.quack.jdbc.QuackProtocolException;
import com.gizmodata.quack.jdbc.codec.HugeIntParts;
import com.gizmodata.quack.jdbc.message.*;
import com.gizmodata.quack.jdbc.transport.QuackTransport;
import com.gizmodata.quack.jdbc.transport.QuackUri;
import com.gizmodata.quack.jdbc.type.LogicalType;
import com.gizmodata.quack.jdbc.type.LogicalTypeId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.SQLException;
import java.util.*;

import static com.gizmodata.quack.jdbc.message.QuackMessage.ResultKind.*;
import static org.junit.jupiter.api.Assertions.*;

class ResultMetadataTest {
    private static final LogicalType BIGINT = LogicalType.of(LogicalTypeId.BIGINT);

    @Test
    void strictHandshakeRejectsStockBeforeCatalogSqlAndDisconnects() {
        var transport = new Transport();
        transport.capability = Optional.empty();
        var error = assertThrows(RuntimeException.class, () -> connect(transport, false, "/catalog"));
        assertTrue(error.getMessage().contains("patched Quack server"));
        assertTrue(error.getMessage().contains("resultMetadata=legacy"));
        assertEquals(List.of(MessageType.CONNECTION_REQUEST, MessageType.DISCONNECT_MESSAGE), transport.requests);
        assertEquals("metadata-test", transport.disconnectedId);
        try (var connection = connect(transport, true, "")) {
            assertFalse(connection.isClosed());
        }
    }

    @Test
    void versionsAreValidatedAndMissingWireVersionIsLegacyOnly() {
        for (boolean legacy : new boolean[]{false, true}) {
            var transport = new Transport();
            transport.version = Optional.of(2L);
            assertThrows(RuntimeException.class, () -> connect(transport, legacy, ""));
            assertEquals("metadata-test", transport.disconnectedId);
            transport.version = Optional.of(1L);
            transport.capability = Optional.of(2L);
            assertThrows(RuntimeException.class, () -> connect(transport, legacy, ""));
        }
        var transport = new Transport();
        transport.version = Optional.empty();
        assertThrows(RuntimeException.class, () -> connect(transport, false, ""));
        try (var ignored = connect(transport, true, "")) { }
    }

    @Test
    void metadataCannotDisappearAfterHandshakeIncludingNegotiatedLegacy() throws Exception {
        for (boolean legacy : new boolean[]{false, true}) {
            var transport = new Transport();
            transport.replies.add(prepare(null, "Count", false, chunk(7)));
            try (var connection = connect(transport, legacy, ""); var statement = connection.createStatement()) {
                SQLException error = assertThrows(SQLException.class, () -> statement.execute("SELECT 7"));
                assertInstanceOf(QuackProtocolException.class, error.getCause());
                assertNull(statement.getResultSet());
                assertEquals(-1, statement.getUpdateCount());
            }
        }
    }

    @Test
    void legacyUsesUnsolicitedValidMetadataAndDoesNotDropItLater() throws Exception {
        var transport = new Transport();
        transport.capability = Optional.empty();
        transport.replies.add(prepare(QUERY, "Count", false, chunk(42)));
        transport.replies.add(prepare(null, "Count", false, chunk(42)));
        try (var connection = connect(transport, true, ""); var statement = connection.createStatement()) {
            assertTrue(statement.execute("SELECT 42::BIGINT AS Count"));
            assertThrows(SQLException.class, () -> statement.execute("SELECT 42::BIGINT AS Count"));
            assertNull(statement.getResultSet());
            assertEquals(-1, statement.getUpdateCount());
        }
    }

    @Test
    void driverAdvertisesRequiredDefaultAndExplicitLegacyChoice() {
        var property = Arrays.stream(new QuackDriver().getPropertyInfo(null, new Properties()))
                .filter(info -> info.name.equals("resultMetadata")).findFirst().orElseThrow();
        assertEquals("required", property.value);
        assertArrayEquals(new String[]{"required", "legacy"}, property.choices);
        assertTrue(property.description.contains("ambiguous"));
    }

    @Test
    void metadataWinsOverSqlAndAliasesEvenInLegacy() throws Exception {
        for (boolean legacy : new boolean[]{false, true}) {
            var transport = new Transport();
            transport.replies.add(prepare(QUERY, "Count", false, chunk(42)));
            transport.replies.add(prepare(CHANGED_ROWS, "not_an_alias", false, chunk(3)));
            transport.replies.add(prepare(NOTHING, "Success", false));
            try (var connection = connect(transport, legacy, ""); var statement = connection.createStatement()) {
                assertTrue(statement.execute("UPDATE rewritten_by_authorization"));
                var rows = statement.getResultSet();
                assertTrue(rows.next());
                assertEquals(42, rows.getLong(1));
                assertEquals(-1, statement.getUpdateCount());
                assertFalse(statement.execute("SELECT rewritten_by_authorization"));
                assertTrue(rows.isClosed());
                assertEquals(3, statement.getUpdateCount());
                assertFalse(statement.execute("SELECT rewritten_to_ddl"));
                assertEquals(0, statement.getUpdateCount());
                assertNull(statement.getResultSet());
            }
        }
    }

    @Test
    void countCanArriveInFetchAndExtraRowsStopWithoutDraining() throws Exception {
        var transport = new Transport();
        transport.replies.add(prepare(CHANGED_ROWS, "Count", true));
        transport.replies.add(new QuackMessage.FetchResponse(MessageHeader.of(MessageType.FETCH_RESPONSE),
                List.of(chunk(), chunk(9)), Optional.empty()));
        transport.replies.add(prepare(CHANGED_ROWS, "Count", true, chunk(1), chunk(2)));
        try (var connection = connect(transport, false, ""); var statement = connection.createStatement()) {
            assertEquals(9, statement.executeUpdate("UPDATE t"));
            var error = assertThrows(SQLException.class, () -> statement.execute("UPDATE t"));
            assertInstanceOf(QuackProtocolException.class, error.getCause());
            assertEquals(1, Collections.frequency(transport.requests, MessageType.FETCH_REQUEST));
            assertEquals(-1, statement.getUpdateCount());
        }
    }

    @ParameterizedTest
    @CsvSource({"false, false", "false, true", "true, false", "true, true"})
    void repeatedEmptyContinuationsAreBoundedForCursorAndDrain(boolean drain, boolean zeroRowChunks) {
        var transport = new Transport();
        transport.replies.add(prepare(QUERY, "value", true));
        List<DataChunk> empty = zeroRowChunks ? List.of(chunk(), chunk()) : List.of();
        transport.replies.add(fetch(true, empty));
        transport.replies.add(fetch(true, empty));
        try (var connection = connect(transport, false, ""); var cursor = connection.session().cursor("SELECT value")) {
            var error = assertThrows(QuackProtocolException.class, () -> {
                if (drain) cursor.drainAll();
                else while (cursor.nextChunk() != null) { }
            });
            assertTrue(error.getMessage().contains("without row progress"));
            assertEquals(2, Collections.frequency(transport.requests, MessageType.FETCH_REQUEST));
            assertNull(cursor.nextChunk());
            assertTrue(cursor.drainAll().isEmpty());
            assertEquals(2, Collections.frequency(transport.requests, MessageType.FETCH_REQUEST),
                    "failure must not retry or replay FETCH");
        }
    }

    @ParameterizedTest
    @CsvSource({"false, false", "false, true", "true, false", "true, true"})
    void oneEmptyContinuationThenTerminalRemainsValid(boolean drain, boolean zeroRowChunks) {
        var transport = new Transport();
        transport.replies.add(prepare(QUERY, "value", true));
        List<DataChunk> empty = zeroRowChunks ? List.of(chunk(), chunk()) : List.of();
        transport.replies.add(fetch(true, empty));
        transport.replies.add(fetch(false, empty));
        try (var connection = connect(transport, false, ""); var cursor = connection.session().cursor("SELECT value")) {
            if (drain) assertTrue(cursor.drainAll().stream().allMatch(chunk -> chunk.rowCount() == 0));
            else {
                DataChunk next;
                while ((next = cursor.nextChunk()) != null) assertEquals(0, next.rowCount());
            }
            assertNull(cursor.nextChunk());
            assertEquals(2, Collections.frequency(transport.requests, MessageType.FETCH_REQUEST));
        }
    }

    @ParameterizedTest
    @CsvSource({"false, false", "false, true", "true, false", "true, true"})
    void countValidationRejectsNonprogressBeforeOrAfterCount(boolean countInPrepare, boolean zeroRowChunks)
            throws Exception {
        var transport = new Transport();
        transport.replies.add(prepare(CHANGED_ROWS, "Count", true, countInPrepare ? new DataChunk[]{chunk(9)}
                : new DataChunk[0]));
        List<DataChunk> empty = zeroRowChunks ? List.of(chunk(), chunk()) : List.of();
        transport.replies.add(fetch(true, empty));
        transport.replies.add(fetch(true, empty));
        try (var connection = connect(transport, false, ""); var statement = connection.createStatement()) {
            var error = assertThrows(SQLException.class, () -> statement.executeUpdate("UPDATE t"));
            assertInstanceOf(QuackProtocolException.class, error.getCause());
            assertTrue(error.getMessage().contains("without row progress"));
            assertEquals(2, Collections.frequency(transport.requests, MessageType.FETCH_REQUEST));
            assertEquals(-1, statement.getUpdateCount());
            assertNull(statement.getResultSet());
            assertEquals(1, Collections.frequency(transport.requests, MessageType.PREPARE_REQUEST));
        }
    }

    @ParameterizedTest
    @CsvSource({"false, false", "false, true", "true, false", "true, true"})
    void countValidationAcceptsFinalEmptyContinuationAndTerminal(boolean countInPrepare, boolean zeroRowChunks)
            throws Exception {
        var transport = new Transport();
        transport.replies.add(prepare(CHANGED_ROWS, "Count", true, countInPrepare ? new DataChunk[]{chunk(9)}
                : new DataChunk[0]));
        List<DataChunk> empty = zeroRowChunks ? List.of(chunk(), chunk()) : List.of();
        transport.replies.add(fetch(true, empty));
        transport.replies.add(fetch(false, empty));
        try (var connection = connect(transport, false, ""); var statement = connection.createStatement()) {
            if (countInPrepare) assertEquals(9, statement.executeUpdate("UPDATE t"));
            else {
                var error = assertThrows(SQLException.class, () -> statement.executeUpdate("UPDATE t"));
                assertInstanceOf(QuackProtocolException.class, error.getCause());
                assertTrue(error.getMessage().contains("expected exactly one nonnegative BIGINT count"));
            }
            assertEquals(2, Collections.frequency(transport.requests, MessageType.FETCH_REQUEST));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void positiveRowsResetProgressGuardForCountValidation(boolean zeroRowChunks) throws Exception {
        var transport = new Transport();
        transport.replies.add(prepare(CHANGED_ROWS, "Count", true));
        List<DataChunk> empty = zeroRowChunks ? List.of(chunk(), chunk()) : List.of();
        transport.replies.add(fetch(true, empty));
        transport.replies.add(fetch(true, List.of(chunk(9))));
        transport.replies.add(fetch(true, empty));
        transport.replies.add(fetch(false, empty));
        try (var connection = connect(transport, false, ""); var statement = connection.createStatement()) {
            assertEquals(9, statement.executeUpdate("UPDATE t"));
            assertEquals(4, Collections.frequency(transport.requests, MessageType.FETCH_REQUEST));
        }
    }

    @Test
    void invalidCountsAreProtocolErrorsAndLeaveResetState() throws Exception {
        List<QuackMessage.PrepareResponse> invalid = List.of(
                prepare(CHANGED_ROWS, "Count", false),
                prepare(CHANGED_ROWS, "Count", false, chunk(-1)),
                prepare(CHANGED_ROWS, "Count", false, chunk(1, 2)),
                prepare(CHANGED_ROWS, "Count", false, new DataChunk(1, List.of(BIGINT),
                        List.of(new DecodedVector.ObjectVec(BIGINT, new Object[]{null})))),
                new QuackMessage.PrepareResponse(MessageHeader.of(MessageType.PREPARE_RESPONSE),
                        List.of(LogicalType.of(LogicalTypeId.INTEGER)), List.of("Count"), false,
                        List.of(chunk(1)), new HugeIntParts(0, 0), Optional.of(CHANGED_ROWS)));
        for (var response : invalid) {
            var transport = new Transport();
            transport.replies.add(response);
            try (var connection = connect(transport, false, ""); var statement = connection.createStatement()) {
                var error = assertThrows(SQLException.class, () -> statement.execute("UPDATE t"));
                assertInstanceOf(QuackProtocolException.class, error.getCause());
                assertEquals(-1, statement.getUpdateCount());
                assertNull(statement.getResultSet());
            }
        }
    }

    @Test
    void entrypointMismatchesArePostExecutionAndCloseQueryResults() throws Exception {
        var transport = new Transport();
        transport.replies.add(prepare(QUERY, "Count", true, chunk(42)));
        transport.replies.add(prepare(CHANGED_ROWS, "Count", false, chunk(1)));
        try (var connection = connect(transport, false, ""); var statement = connection.createStatement()) {
            assertThrows(SQLException.class, () -> statement.executeUpdate("SELECT 42 AS Count"));
            assertNull(statement.getResultSet());
            assertEquals(-1, statement.getUpdateCount());
            assertThrows(SQLException.class, () -> statement.executeQuery("UPDATE t"));
            assertEquals(1, statement.getUpdateCount());
            assertEquals(2, Collections.frequency(transport.requests, MessageType.PREPARE_REQUEST));
            assertEquals(0, Collections.frequency(transport.requests, MessageType.FETCH_REQUEST));
        }
    }

    @Test
    void explicitUnnegotiatedLegacyStillHasAliasLimitation() throws Exception {
        var transport = new Transport();
        transport.capability = Optional.empty();
        transport.replies.add(prepare(null, "Count", false, chunk(42)));
        try (var connection = connect(transport, true, ""); var statement = connection.createStatement()) {
            assertFalse(statement.execute("SELECT 42::BIGINT AS Count"));
            assertEquals(42, statement.getUpdateCount());
        }
    }

    private static QuackConnection connect(Transport transport, boolean legacy, String path) {
        return new QuackConnection(QuackUri.parse("jdbc:quack://example.test" + path
                + (legacy ? "?resultMetadata=legacy" : "")), uri -> transport);
    }

    private static DataChunk chunk(long... values) {
        return new DataChunk(values.length, List.of(BIGINT), List.of(new DecodedVector.LongVec(BIGINT, values, null)));
    }

    private static QuackMessage.FetchResponse fetch(boolean more, List<DataChunk> chunks) {
        return new QuackMessage.FetchResponse(MessageHeader.of(MessageType.FETCH_RESPONSE), chunks,
                more ? Optional.of(1L) : Optional.empty());
    }

    private static QuackMessage.PrepareResponse prepare(QuackMessage.ResultKind kind, String name,
                                                        boolean more, DataChunk... chunks) {
        return new QuackMessage.PrepareResponse(MessageHeader.of(MessageType.PREPARE_RESPONSE), List.of(BIGINT),
                List.of(name), more, List.of(chunks), new HugeIntParts(0, 0), Optional.ofNullable(kind));
    }

    private static final class Transport implements QuackTransport {
        Optional<Long> capability = Optional.of(1L);
        Optional<Long> version = Optional.of(1L);
        final Deque<QuackMessage> replies = new ArrayDeque<>();
        final List<MessageType> requests = new ArrayList<>();
        String disconnectedId;
        @Override public QuackMessage send(QuackMessage request) {
            requests.add(request.header().type());
            if (request instanceof QuackMessage.ConnectionRequest) {
                return new QuackMessage.ConnectionResponse(
                        MessageHeader.of(MessageType.CONNECTION_RESPONSE).withConnectionId("metadata-test"),
                        Optional.of("v1.5.5"), Optional.empty(), version, capability);
            }
            if (request instanceof QuackMessage.DisconnectMessage) {
                disconnectedId = request.header().connectionId().orElseThrow();
                return new QuackMessage.SuccessResponse(MessageHeader.of(MessageType.SUCCESS_RESPONSE));
            }
            assertFalse(replies.isEmpty(), "Unexpected request or unbounded drain");
            return replies.removeFirst();
        }
    }
}

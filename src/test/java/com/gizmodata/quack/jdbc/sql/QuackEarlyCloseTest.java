package com.gizmodata.quack.jdbc.sql;

import com.gizmodata.quack.jdbc.QuackServerException;
import com.gizmodata.quack.jdbc.codec.HugeIntParts;
import com.gizmodata.quack.jdbc.message.DataChunk;
import com.gizmodata.quack.jdbc.message.DecodedVector;
import com.gizmodata.quack.jdbc.message.MessageHeader;
import com.gizmodata.quack.jdbc.message.MessageType;
import com.gizmodata.quack.jdbc.message.QuackMessage;
import com.gizmodata.quack.jdbc.transport.QuackTransport;
import com.gizmodata.quack.jdbc.transport.QuackUri;
import com.gizmodata.quack.jdbc.type.LogicalType;
import com.gizmodata.quack.jdbc.type.LogicalTypeId;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class QuackEarlyCloseTest {
    private static final LogicalType INTEGER = LogicalType.of(LogicalTypeId.INTEGER);
    private static final DataChunk ROW = new DataChunk(1, List.of(INTEGER),
            List.of(new DecodedVector.IntVec(INTEGER, new int[]{1}, null)));

    @ParameterizedTest
    @ValueSource(longs = {1, 3})
    void closeCompletesStreamBeforeNextPrepare(long version) throws Exception {
        Server server = new Server(version, true, 0);
        try (var connection = server.connect(); var statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            var result = statement.executeQuery("SELECT first");
            assertTrue(result.next());
            result.close();
            result.close();
            assertEquals(1, server.fetches, "more=true needs a terminal FETCH, even after the only row");
            statement.execute("SELECT next");
            assertEquals(List.of("BEGIN TRANSACTION", "SELECT first", "FETCH 1", "SELECT next"), server.events);
        }
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 3})
    void completeInlineResultDoesNotFetch(long version) throws Exception {
        Server server = new Server(version, false, 0);
        try (var connection = server.connect(); var statement = connection.createStatement()) {
            var result = statement.executeQuery("SELECT first");
            result.close();
            statement.execute("SELECT next");
            assertEquals(0, server.fetches);
        }
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 3})
    void reexecutionDrainsUnreadBatchesAndTerminalFetch(long version) throws Exception {
        Server server = new Server(version, true, 3);
        try (var connection = server.connect(); var statement = connection.createStatement()) {
            var result = statement.executeQuery("SELECT first");
            statement.execute("SELECT next");
            assertTrue(result.isClosed());
            assertEquals(4, server.fetches, "Discard all unread batches plus terminal response");
            assertEquals("SELECT next", server.events.get(server.events.size() - 1));
        }
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 3})
    void closeFetchFailureIsJdbcErrorAndIsNotRetried(long version) throws Exception {
        Server server = new Server(version, true, 0);
        try (var connection = server.connect(); var statement = connection.createStatement()) {
            var result = statement.executeQuery("SELECT first");
            server.failure = new QuackServerException("late statement failure");
            SQLException error = assertThrows(SQLException.class, result::close);
            assertSame(server.failure, error.getCause());
            assertTrue(result.isClosed());
            result.close();
            assertEquals(1, server.fetches);
        }
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 3})
    void reexecutionDoesNotSendPrepareAfterCloseFailure(long version) throws Exception {
        Server server = new Server(version, true, 0);
        try (var connection = server.connect(); var statement = connection.createStatement()) {
            statement.executeQuery("SELECT first");
            server.failure = new QuackServerException("late statement failure");
            assertThrows(SQLException.class, () -> statement.execute("SELECT next"));
            assertFalse(server.events.contains("SELECT next"));
            assertNull(statement.getResultSet());
            assertEquals(-1, statement.getUpdateCount());
        }
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 3})
    void differentStatementAndCommitAlsoFinishPreviousStream(long version) throws Exception {
        Server server = new Server(version, true, 2);
        try (var connection = server.connect(); var old = connection.createStatement();
             var next = connection.createStatement()) {
            connection.setAutoCommit(false);
            old.executeQuery("SELECT first");
            next.execute("SELECT next");
            assertEquals(3, server.fetches);
            old.close();
            assertEquals(3, server.fetches, "An old cursor must never fetch against a new query");
        }
        server = new Server(version, true, 2);
        try (var connection = server.connect(); var statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.executeQuery("SELECT first");
            connection.commit();
            assertEquals(3, server.fetches);
            assertEquals("COMMIT", server.events.get(server.events.size() - 1));
        }
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 3})
    void allJdbcImplicitClosePathsSurfaceLateErrors(long version) throws Exception {
        for (String operation : List.of("statementClose", "moreResults", "moreResultsFlag", "prepared")) {
            Server server = new Server(version, true, 0);
            try (var connection = server.connect();
                 var statement = connection.prepareStatement("SELECT first")) {
                statement.executeQuery();
                server.failure = new QuackServerException("late " + operation);
                SQLException error = assertThrows(SQLException.class, () -> {
                    switch (operation) {
                        case "statementClose": statement.close(); break;
                        case "moreResults": statement.getMoreResults(); break;
                        case "moreResultsFlag": statement.getMoreResults(java.sql.Statement.CLOSE_CURRENT_RESULT); break;
                        case "prepared": statement.executeQuery(); break;
                        default: fail("Unknown close path");
                    }
                });
                assertSame(server.failure, error.getCause());
                assertEquals(1, server.fetches);
                assertEquals(List.of("SELECT first", "FETCH 1"), server.events);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 3})
    void lateDmlFailureDoesNotPublishAnUpdateCount(long version) throws Exception {
        Server server = new Server(version, true, 0);
        try (var connection = server.connect(); var statement = connection.createStatement()) {
            server.failure = new QuackServerException("late write failure");
            SQLException error = assertThrows(SQLException.class, () -> statement.executeUpdate("INSERT INTO t VALUES (1)"));
            assertSame(server.failure, error.getCause());
            assertEquals(-1, statement.getUpdateCount());
            assertNull(statement.getResultSet());
        }
    }

    private static final class Server implements QuackTransport {
        final long version;
        final boolean more;
        final int unreadBatches;
        final List<String> events = new ArrayList<>();
        HugeIntParts resultUuid;
        int fetches;
        RuntimeException failure;

        Server(long version, boolean more, int unreadBatches) {
            this.version = version;
            this.more = more;
            this.unreadBatches = unreadBatches;
        }

        QuackConnection connect() {
            return new QuackConnection(QuackUri.parse("jdbc:quack://example.test"), uri -> this);
        }

        @Override public QuackMessage send(QuackMessage request) {
            if (request instanceof QuackMessage.ConnectionRequest) {
                return new QuackMessage.ConnectionResponse(MessageHeader.of(MessageType.CONNECTION_RESPONSE)
                        .withConnectionId("early-close"), Optional.empty(), Optional.empty(), Optional.of(version),
                        version == 3 ? Optional.of(60L) : Optional.empty());
            }
            if (request instanceof QuackMessage.PrepareRequest prepare) {
                events.add(prepare.sql());
                boolean dml = prepare.sql().startsWith("INSERT");
                boolean first = prepare.sql().equals("SELECT first") || dml;
                if (first) resultUuid = prepare.queryUuid();
                return new QuackMessage.PrepareResponse(MessageHeader.of(MessageType.PREPARE_RESPONSE),
                        List.of(INTEGER), List.of(dml ? "Count" : "value"), first && more, List.of(ROW), prepare.queryUuid());
            }
            if (request instanceof QuackMessage.FetchRequest fetch) {
                fetches++;
                events.add("FETCH " + fetches);
                assertEquals(resultUuid, fetch.resultUuid());
                if (version == 3) assertEquals(fetches, fetch.batchIndex());
                if (failure != null) throw failure;
                boolean batch = fetches <= unreadBatches;
                return new QuackMessage.FetchResponse(MessageHeader.of(MessageType.FETCH_RESPONSE),
                        batch ? List.of(ROW) : List.of(), batch ? Optional.of((long) fetches) : Optional.empty(),
                        !batch && version == 3 ? Optional.of((long) unreadBatches) : Optional.empty());
            }
            assertInstanceOf(QuackMessage.DisconnectMessage.class, request, "Close must never CANCEL");
            return new QuackMessage.SuccessResponse(MessageHeader.of(MessageType.SUCCESS_RESPONSE));
        }
    }
}

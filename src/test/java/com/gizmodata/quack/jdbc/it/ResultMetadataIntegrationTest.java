package com.gizmodata.quack.jdbc.it;

import com.gizmodata.quack.jdbc.codec.HugeIntParts;
import com.gizmodata.quack.jdbc.message.MessageCodec;
import com.gizmodata.quack.jdbc.message.MessageHeader;
import com.gizmodata.quack.jdbc.message.MessageType;
import com.gizmodata.quack.jdbc.message.QuackMessage;
import com.gizmodata.quack.jdbc.sql.QuackConnection;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** B15 qualification uses the patched fixture, never a legacy URL. */
class ResultMetadataIntegrationTest {
    private static final String METADATA_HEADER = "X-Quack-Result-Metadata";
    private static final String KIND_HEADER = "X-Quack-Result-Kind";
    private static QuackServerFixture server;

    @BeforeAll
    static void start() throws Exception {
        String artifact = System.getenv("QUACK_IT_RESULT_METADATA_EXTENSION");
        assumeTrue(QuackServerFixture.required() || (artifact != null && !artifact.isBlank()),
                "B15 classification requires QUACK_IT_RESULT_METADATA_EXTENSION");
        server = QuackServerFixture.tryStart();
        assertNotNull(server, "Configured B15 artifact requires a working DuckDB fixture");
        assertFalse(server.jdbcUrl().contains("resultMetadata=legacy"));
    }

    @AfterAll
    static void stop() { if (server != null) server.close(); }

    @ParameterizedTest
    @ValueSource(strings = {"SELECT 42::BIGINT AS Count", "SELECT 42::BIGINT AS rows_affected",
            "SELECT 42::BIGINT AS Count WHERE false", "SELECT 42::BIGINT AS rows_affected WHERE false",
            "WITH c AS (SELECT 42::BIGINT AS Count) SELECT * FROM c", "EXPLAIN SELECT 42"})
    void queriesRemainResultSetsRegardlessOfAliasesOrCardinality(String sql) throws Exception {
        try (var connection = DriverManager.getConnection(server.jdbcUrl()); var statement = connection.createStatement()) {
            assertTrue(statement.execute(sql));
            assertEquals(-1, statement.getUpdateCount());
            assertNotNull(statement.getResultSet());
            assertEquals(!sql.contains("WHERE false"), statement.getResultSet().next());
        }
    }

    @Test
    void dmlCountsAndDdlHaveNoResultSetIncludingCtasAndCtes() throws Exception {
        try (var connection = DriverManager.getConnection(server.jdbcUrl()); var statement = connection.createStatement()) {
            assertFalse(statement.execute("CREATE TEMP TABLE counts (v BIGINT)"));
            assertEquals(0, statement.getUpdateCount());
            assertNull(statement.getResultSet());
            assertEquals(2, statement.executeUpdate("INSERT INTO counts VALUES (1), (2)"));
            assertEquals(0, statement.executeUpdate("INSERT INTO counts SELECT 3 WHERE false"));
            assertEquals(2, statement.executeUpdate("WITH c AS (SELECT 10 AS v) UPDATE counts SET v = (SELECT v FROM c)"));
            assertEquals(0, statement.executeUpdate("UPDATE counts SET v = 20 WHERE false"));
            assertEquals(0, statement.executeUpdate("DELETE FROM counts WHERE false"));
            assertEquals(2, statement.executeUpdate("WITH c AS (SELECT 10 AS v) DELETE FROM counts WHERE v IN (SELECT v FROM c)"));
            assertEquals(1, statement.executeUpdate("WITH c AS (SELECT 7 AS v) INSERT INTO counts SELECT * FROM c"));
            assertEquals(0, statement.executeUpdate("CREATE TEMP TABLE copied AS SELECT * FROM counts"));
            try (var rows = statement.executeQuery("SELECT count(*) FROM copied")) {
                assertTrue(rows.next());
                assertEquals(1, rows.getLong(1));
            }
            assertEquals(0, statement.executeUpdate("DROP TABLE copied"));
            assertNull(statement.getResultSet());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"INSERT INTO returning_test VALUES (1) RETURNING v AS Count",
            "INSERT INTO returning_test SELECT 1 WHERE false RETURNING v AS Count",
            "UPDATE returning_test SET v = 2 RETURNING v AS Count",
            "UPDATE returning_test SET v = 2 WHERE false RETURNING v AS Count",
            "DELETE FROM returning_test RETURNING v AS rows_affected",
            "DELETE FROM returning_test WHERE false RETURNING v AS Count"})
    void returningIsAlwaysQueryIncludingEmptyResults(String sql) throws Exception {
        try (var connection = DriverManager.getConnection(server.jdbcUrl()); var statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TEMP TABLE returning_test (v BIGINT)");
            statement.executeUpdate("INSERT INTO returning_test VALUES (5)");
            assertTrue(statement.execute(sql));
            assertEquals(-1, statement.getUpdateCount());
            assertEquals(!sql.contains("WHERE false"), statement.getResultSet().next());
        }
    }

    @Test
    void typedEntrypointsRejectMismatchesAfterExecutionNotBefore() throws Exception {
        try (var connection = DriverManager.getConnection(server.jdbcUrl()); var statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TEMP TABLE mismatches (v BIGINT)");
            assertThrows(SQLException.class, () -> statement.executeUpdate("SELECT 42::BIGINT AS Count"));
            assertNull(statement.getResultSet());
            assertEquals(-1, statement.getUpdateCount());
            assertThrows(SQLException.class, () -> statement.executeQuery("INSERT INTO mismatches VALUES (1)"));
            assertEquals(1, statement.getUpdateCount());
            assertThrows(SQLException.class,
                    () -> statement.executeUpdate("INSERT INTO mismatches VALUES (2) RETURNING v AS Count"));
            assertNull(statement.getResultSet());
            try (var rows = statement.executeQuery("SELECT count(*) FROM mismatches")) {
                assertTrue(rows.next());
                assertEquals(2, rows.getLong(1), "mismatch checks do not undo already executed SQL");
            }
        }
    }

    @Test
    void authorizationRewriteUsesActualExecutedReturnKind() throws Exception {
        try (var connection = DriverManager.getConnection(server.jdbcUrl()); var statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TEMP TABLE rewritten (v BIGINT)");
            statement.executeUpdate("CREATE MACRO b15_authorize(session_id, query_text) AS CASE query_text "
                    + "WHEN 'UPDATE rewritten SET v = 99' THEN 'SELECT 42::BIGINT AS Count' "
                    + "WHEN 'SELECT 123' THEN 'INSERT INTO rewritten VALUES (7)' ELSE query_text END");
            statement.execute("SET GLOBAL quack_authorization_function = 'b15_authorize'");
            try {
                assertTrue(statement.execute("UPDATE rewritten SET v = 99"));
                assertTrue(statement.getResultSet().next());
                assertEquals(42, statement.getResultSet().getLong(1));
                assertFalse(statement.execute("SELECT 123"));
                assertEquals(1, statement.getUpdateCount());
            } finally {
                statement.execute("SET GLOBAL quack_authorization_function = 'quack_nop_authorization'");
                statement.execute("DROP MACRO b15_authorize");
            }
        }
    }

    @Test
    void stockServerRequiresExplicitLegacyAndStillHasAliasLimitation() throws Exception {
        try (var stock = QuackServerFixture.tryStartLegacy()) {
            assertNotNull(stock);
            String required = stock.jdbcUrl().replace("&resultMetadata=legacy", "");
            var error = assertThrows(SQLException.class, () -> DriverManager.getConnection(required));
            assertTrue(error.getMessage().contains("patched Quack server"));
            try (var connection = DriverManager.getConnection(stock.jdbcUrl()); var statement = connection.createStatement()) {
                assertFalse(statement.execute("SELECT 42::BIGINT AS Count"));
                assertEquals(42, statement.getUpdateCount(), "legacy explicitly retains B15's alias limitation");
            }
        }
    }

    @Test
    void invalidRequestNegotiationFailsBeforeDecodeSqlOrConnectionCreation() throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        try (var connection = DriverManager.getConnection(server.jdbcUrl()); var statement = connection.createStatement()) {
            statement.executeUpdate("CREATE TEMP TABLE negotiation_side_effects (v INTEGER)");
            long beforeConnections;
            try (var rows = statement.executeQuery("SELECT sum(active_connections) FROM quack_server_list()")) {
                assertTrue(rows.next());
                beforeConnections = rows.getLong(1);
            }
            String connectionId = assertInstanceOf(QuackConnection.class, connection).session().connectionId();
            byte[] insert = MessageCodec.encode(new QuackMessage.PrepareRequest(
                    MessageHeader.of(MessageType.PREPARE_REQUEST).withConnectionId(connectionId),
                    "INSERT INTO negotiation_side_effects VALUES (9999)"));
            List<String[]> invalidHeaders = new ArrayList<>();
            for (String value : List.of("", "0", "2", "01", "1, 1", "1, 2", "bogus")) {
                invalidHeaders.add(new String[]{METADATA_HEADER, value});
            }
            invalidHeaders.add(new String[]{METADATA_HEADER, "1", METADATA_HEADER, "1"});
            invalidHeaders.add(new String[]{METADATA_HEADER, "1", "x-quack-result-metadata", "2"});
            for (String[] headers : invalidHeaders) {
                for (byte[] payload : List.of("malformed".getBytes(StandardCharsets.US_ASCII),
                        MessageCodec.encode(connectionRequest()), insert)) {
                    // Early rejection leaves the body unread; isolate probes as the manual HTTP tests do.
                    var response = rawPost(HttpClient.newHttpClient(), payload, headers);
                    assertEquals(400, response.statusCode());
                    assertEquals("close", response.headers().firstValue("Connection").orElseThrow());
                    assertArrayEquals("Invalid X-Quack-Result-Metadata header\n".getBytes(StandardCharsets.US_ASCII),
                            response.body(), "request=" + java.util.Arrays.toString(headers)
                                    + "; response=" + response.headers());
                    assertEquals(Optional.of("text/plain"), response.headers().firstValue("Content-Type"));
                    assertTrue(response.headers().allValues(METADATA_HEADER).isEmpty());
                    assertTrue(response.headers().allValues(KIND_HEADER).isEmpty());
                }
            }
            try (var rows = statement.executeQuery("SELECT count(*) FROM negotiation_side_effects")) {
                assertTrue(rows.next());
                assertEquals(0, rows.getLong(1), "rejected requests must not execute their INSERT");
            }
            try (var rows = statement.executeQuery("SELECT sum(active_connections) FROM quack_server_list()")) {
                assertTrue(rows.next());
                assertEquals(beforeConnections, rows.getLong(1), "rejected requests must not create connections");
            }
            // Prove that the rejected SQL was executable and would have changed the table.
            var accepted = rawPost(client, insert, METADATA_HEADER, "1");
            assertEquals(200, accepted.statusCode());
            assertInstanceOf(QuackMessage.PrepareResponse.class, MessageCodec.decode(accepted.body()));
            assertEquals(Optional.of("changed_rows"), accepted.headers().firstValue(KIND_HEADER));
            try (var rows = statement.executeQuery("SELECT count(*) FROM negotiation_side_effects")) {
                assertTrue(rows.next());
                assertEquals(1, rows.getLong(1));
            }
        }
    }

    @Test
    void realHttpMetadataLeavesLegacyV1ConnectionAndPrepareBodiesUnchanged() throws Exception {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        List<byte[]> legacyBodies = new ArrayList<>();
        for (boolean metadata : new boolean[]{false, true}) {
            String[] headers = metadata ? new String[]{"x-quack-result-metadata", "1"} : new String[0];
            var connected = rawPost(client, MessageCodec.encode(connectionRequest()), headers);
            assertEquals(200, connected.statusCode());
            var connection = assertInstanceOf(QuackMessage.ConnectionResponse.class, MessageCodec.decode(connected.body()));
            String connectionId = connection.header().connectionId().orElseThrow();
            try {
                assertEquals(Optional.of(1L), connection.quackVersion());
                assertTrue(connection.resultMetadataVersion().isEmpty(), "HTTP metadata is not part of the v1 body");
                assertEquals(metadata ? List.of("1") : List.of(), connected.headers().allValues(METADATA_HEADER));
                assertTrue(connected.headers().allValues(KIND_HEADER).isEmpty());
                // Normalize only per-execution identifiers before comparing codec output.
                byte[] normalizedConnection = MessageCodec.encode(new QuackMessage.ConnectionResponse(
                        MessageHeader.of(MessageType.CONNECTION_RESPONSE), connection.serverDuckdbVersion(),
                        connection.serverPlatform(), connection.quackVersion()));
                if (metadata) assertArrayEquals(legacyBodies.get(0), normalizedConnection);
                else legacyBodies.add(normalizedConnection);
                String[] sql = {"CREATE TEMP TABLE wire_compat (v INTEGER)",
                        "SELECT 42::BIGINT AS Count WHERE false", "INSERT INTO wire_compat SELECT 1 WHERE false"};
                String[] kinds = {"nothing", "query", "changed_rows"};
                for (int i = 0; i < sql.length; i++) {
                    var response = rawPost(client, MessageCodec.encode(new QuackMessage.PrepareRequest(
                            MessageHeader.of(MessageType.PREPARE_REQUEST).withConnectionId(connectionId), sql[i])), headers);
                    assertEquals(200, response.statusCode());
                    var prepare = assertInstanceOf(QuackMessage.PrepareResponse.class, MessageCodec.decode(response.body()));
                    assertTrue(prepare.resultKind().isEmpty(), "result kind must remain HTTP-only");
                    assertEquals(metadata ? List.of("1") : List.of(), response.headers().allValues(METADATA_HEADER));
                    assertEquals(metadata ? List.of(kinds[i]) : List.of(), response.headers().allValues(KIND_HEADER));
                    byte[] normalized = MessageCodec.encode(new QuackMessage.PrepareResponse(
                            MessageHeader.of(MessageType.PREPARE_RESPONSE), prepare.resultTypes(), prepare.resultNames(),
                            prepare.needsMoreFetch(), prepare.results(), new HugeIntParts(0, 0)));
                    if (metadata) assertArrayEquals(legacyBodies.get(i + 1), normalized, sql[i]);
                    else legacyBodies.add(normalized);
                }
            } finally {
                var disconnected = rawPost(client, MessageCodec.encode(new QuackMessage.DisconnectMessage(
                        MessageHeader.of(MessageType.DISCONNECT_MESSAGE).withConnectionId(connectionId))), headers);
                assertEquals(200, disconnected.statusCode());
                assertInstanceOf(QuackMessage.SuccessResponse.class, MessageCodec.decode(disconnected.body()));
            }
        }
    }

    private static QuackMessage.ConnectionRequest connectionRequest() {
        return new QuackMessage.ConnectionRequest(MessageHeader.of(MessageType.CONNECTION_REQUEST),
                Optional.of(server.token()), Optional.empty(), Optional.empty(), Optional.of(1L), Optional.of(1L));
    }

    private static HttpResponse<byte[]> rawPost(HttpClient client, byte[] body, String... headers) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.port() + "/quack"))
                .version(HttpClient.Version.HTTP_1_1)
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/vnd.duckdb")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        if (headers.length > 0) request.headers(headers);
        return client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }
}

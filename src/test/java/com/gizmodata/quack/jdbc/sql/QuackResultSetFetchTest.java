package com.gizmodata.quack.jdbc.sql;

import com.gizmodata.quack.jdbc.QuackProtocolException;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.sql.Date;
import java.sql.SQLException;
import java.sql.Time;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.Calendar;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.TimeZone;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuackResultSetFetchTest {
    private static final LogicalType INTEGER = LogicalType.of(LogicalTypeId.INTEGER);
    private static final HugeIntParts RESULT_UUID = new HugeIntParts(17, 23);

    @ParameterizedTest
    @MethodSource("fetchFailures")
    void failedFetchAfterInitialPageInvalidatesRowAndIsNeverRetried(RuntimeException failure)
            throws SQLException {
        var transport = new ScriptedTransport(failure, fetch(false, chunk(INTEGER, 30)));
        DataChunk initial = chunk(INTEGER, 10, 20);
        var cursor = cursor(transport, INTEGER, true, initial);
        try (var rs = new QuackResultSet(null, cursor)) {
            assertSame(initial, cursor.peekFirstChunk());
            assertGettersOffRow(rs);
            assertEquals(0, transport.fetches);
            assertTrue(rs.next());
            assertEquals(10, rs.getInt(1));
            assertEquals(1, rs.getRow());
            assertTrue(rs.next());
            assertEquals(20, rs.getInt("value"));
            assertEquals(2, rs.getRow());
            assertEquals(0, transport.fetches);

            SQLException error = assertThrows(SQLException.class, rs::next);
            assertSame(failure, error.getCause());
            assertTrue(error.getMessage().contains(failure.getMessage()));
            assertEquals(1, transport.fetches);
            assertFalse(rs.isClosed(), "FETCH failure exhausts rather than closes the ResultSet");
            assertGettersOffRow(rs);
            assertEquals(1, rs.getMetaData().getColumnCount());
            for (int i = 0; i < 2; i++) {
                assertFalse(rs.next());
                assertGettersOffRow(rs);
            }
            assertNull(cursor.nextChunk(), "The failed cursor must also be closed");
            assertEquals(1, transport.fetches, "A failed FETCH must not be replayed");
            assertEquals(2, cursor.materializedRowCount());

            rs.close();
            assertTrue(rs.isClosed());
            assertThrows(SQLException.class, rs::next);
            assertThrows(SQLException.class, () -> rs.getInt(1));
            assertEquals(1, transport.fetches);
        }
    }

    private static Stream<RuntimeException> fetchFailures() {
        return Stream.of(
                new QuackServerException("server FETCH failed"),
                new QuackProtocolException("invalid FETCH response", new IOException("truncated body")),
                new IllegalStateException("transport FETCH failed", new IOException("connection lost")));
    }

    @Test
    void streamsInitialAndFetchedChunksLazilyIncludingNullsAndEmptyChunks() throws SQLException {
        var transport = new ScriptedTransport(
                fetch(true, chunk(INTEGER), chunk(INTEGER, null, 30), chunk(INTEGER)),
                fetch(false, chunk(INTEGER, 40), chunk(INTEGER)));
        var cursor = cursor(transport, INTEGER, true,
                chunk(INTEGER), chunk(INTEGER, 10, null), chunk(INTEGER),
                chunk(INTEGER, 20), chunk(INTEGER));
        try (var rs = new QuackResultSet(null, cursor)) {
            assertGettersOffRow(rs);
            assertEquals(0, transport.fetches);
            assertEquals(3, cursor.materializedRowCount());
            Integer[] expected = {10, null, 20, null, 30, 40};
            int[] fetchCounts = {0, 0, 0, 1, 1, 2};
            for (int i = 0; i < expected.length; i++) {
                assertTrue(rs.next());
                assertEquals(i + 1, rs.getRow());
                assertEquals(expected[i], rs.getObject(1));
                assertEquals(expected[i] == null, rs.wasNull());
                assertEquals(expected[i], rs.getObject("value", Integer.class));
                assertEquals(expected[i] == null ? 0 : expected[i].intValue(), rs.getInt("value"));
                assertEquals(expected[i] == null, rs.wasNull());
                assertEquals(expected[i] == null ? null : expected[i].toString(), rs.getString(1));
                assertEquals(expected[i] == null, rs.wasNull());
                assertEquals(fetchCounts[i], transport.fetches);
            }
            assertFalse(rs.next());
            assertGettersOffRow(rs);
            assertFalse(rs.next());
            assertGettersOffRow(rs);
            assertFalse(rs.isClosed());
            assertEquals(2, transport.fetches);
            assertEquals(6, cursor.materializedRowCount());
        }
    }

    @Test
    void skipsZeroRowChunksEvenWhenAnEntireFetchedPageContainsNoRows() throws SQLException {
        var transport = new ScriptedTransport(
                fetch(true, chunk(INTEGER), chunk(INTEGER)),
                fetch(false, chunk(INTEGER), chunk(INTEGER, 7)));
        try (var rs = new QuackResultSet(null, cursor(transport, INTEGER, true))) {
            assertGettersOffRow(rs);
            assertEquals(0, transport.fetches);
            assertTrue(rs.next());
            assertEquals(7, rs.getInt(1));
            assertEquals(1, rs.getRow());
            assertEquals(2, transport.fetches);
            assertFalse(rs.next());
            assertGettersOffRow(rs);
            assertFalse(rs.next());
            assertEquals(2, transport.fetches);
        }
    }

    @Test
    void emptyTerminalFetchInvalidatesTheLastRow() throws SQLException {
        var transport = new ScriptedTransport(fetch(false));
        try (var rs = new QuackResultSet(null, cursor(transport, INTEGER, true, chunk(INTEGER, 1)))) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
            assertEquals(0, transport.fetches);
            assertFalse(rs.next());
            assertGettersOffRow(rs);
            assertFalse(rs.next());
            assertFalse(rs.isClosed());
            assertEquals(1, transport.fetches);
        }
    }

    @Test
    void emptyInitialResultNeverFetchesOrExposesARow() throws SQLException {
        var transport = new ScriptedTransport();
        try (var rs = new QuackResultSet(null, cursor(transport, INTEGER, false))) {
            assertGettersOffRow(rs);
            assertFalse(rs.next());
            assertGettersOffRow(rs);
            assertFalse(rs.next());
            assertFalse(rs.isClosed());
            assertEquals(0, transport.fetches);
        }
    }

    @Test
    void failureWhileSkippingEmptyChunksAlsoLeavesNoRowOrRetry() throws SQLException {
        var failure = new QuackProtocolException("FETCH failed after empty chunks");
        var transport = new ScriptedTransport(fetch(true, chunk(INTEGER)), failure);
        var cursor = cursor(transport, INTEGER, true, chunk(INTEGER));
        try (var rs = new QuackResultSet(null, cursor)) {
            assertSame(failure, assertThrows(SQLException.class, rs::next).getCause());
            assertGettersOffRow(rs);
            assertFalse(rs.next());
            assertNull(cursor.nextChunk());
            assertEquals(2, transport.fetches);
        }
    }

    @Test
    void temporalAndCalendarGettersKeepTheirBehaviorAcrossPages() throws SQLException {
        LogicalType type = LogicalType.of(LogicalTypeId.TIMESTAMP_NS);
        LocalDateTime first = LocalDateTime.parse("2024-01-02T03:04:05.123456789");
        LocalDateTime second = LocalDateTime.parse("2024-07-08T09:10:11.987654321");
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("GMT+09:00"));
        Calendar saved = (Calendar) cal.clone();
        ZoneOffset offset = ZoneOffset.ofHours(9);
        var transport = new ScriptedTransport(fetch(false, chunk(type, second, null)));
        try (var rs = new QuackResultSet(null, cursor(transport, type, true, chunk(type, first, null)))) {
            LocalDateTime[] expected = {first, null, second, null};
            for (int i = 0; i < expected.length; i++) {
                LocalDateTime value = expected[i];
                assertTrue(rs.next());
                assertEquals(value, rs.getObject("value", LocalDateTime.class));
                assertEquals(value == null, rs.wasNull());
                if (value == null) {
                    assertNull(rs.getTimestamp(1));
                    assertNull(rs.getTimestamp("value", cal));
                    assertNull(rs.getDate(1, cal));
                    assertNull(rs.getTime("value", cal));
                    assertTrue(rs.wasNull());
                } else {
                    assertEquals(Timestamp.valueOf(value), rs.getTimestamp(1));
                    assertEquals(Date.valueOf(value.toLocalDate()), rs.getDate(1));
                    assertEquals(Time.valueOf(value.toLocalTime()), rs.getTime(1));
                    assertEquals(rs.getTimestamp(1), rs.getTimestamp("value", null));
                    assertEquals(value.toInstant(offset), rs.getTimestamp("value", cal).toInstant());
                    assertEquals(value.toLocalDate().atStartOfDay().toInstant(offset).toEpochMilli(),
                            rs.getDate(1, cal).getTime());
                    assertEquals(value.toLocalTime().atDate(LocalDate.ofEpochDay(0))
                                    .toInstant(offset).toEpochMilli(), rs.getTime("value", cal).getTime());
                    assertFalse(rs.wasNull());
                }
                assertEquals(saved, cal);
                assertEquals(i < 2 ? 0 : 1, transport.fetches);
            }
            assertFalse(rs.next());
            assertGettersOffRow(rs);
            assertEquals(1, transport.fetches);
        }
    }

    private static void assertGettersOffRow(QuackResultSet rs) {
        assertEquals(0, rs.getRow());
        assertEquals("Not on a row", assertThrows(SQLException.class, () -> rs.getObject(1)).getMessage());
        assertThrows(SQLException.class, () -> rs.getObject("value"));
        assertThrows(SQLException.class, () -> rs.getObject("value", Integer.class));
        assertThrows(SQLException.class, () -> rs.getInt(1));
        assertThrows(SQLException.class, () -> rs.getString("value"));
        Calendar cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"));
        assertThrows(SQLException.class, () -> rs.getDate(1, cal));
        assertThrows(SQLException.class, () -> rs.getTime("value", cal));
        assertThrows(SQLException.class, () -> rs.getTimestamp(1, cal));
    }

    private static DataChunk chunk(LogicalType type, Object... values) {
        return new DataChunk(values.length, List.of(type),
                List.of(new DecodedVector.ObjectVec(type, values)));
    }

    private static QuackMessage.FetchResponse fetch(boolean more, DataChunk... chunks) {
        return new QuackMessage.FetchResponse(MessageHeader.of(MessageType.FETCH_RESPONSE),
                List.of(chunks), more ? Optional.of(1L) : Optional.empty());
    }

    private static QuackSession.Cursor cursor(ScriptedTransport transport, LogicalType type,
                                               boolean more, DataChunk... initialChunks) {
        var session = new QuackSession(QuackUri.parse("jdbc:quack://example.test?resultMetadata=legacy"), transport);
        var response = new QuackMessage.PrepareResponse(MessageHeader.of(MessageType.PREPARE_RESPONSE),
                List.of(type), List.of("value"), more, List.of(initialChunks), RESULT_UUID);
        return new QuackSession.Cursor(session, response);
    }

    private static final class ScriptedTransport implements QuackTransport {
        private final Deque<Object> replies;
        private int fetches;

        private ScriptedTransport(Object... replies) {
            this.replies = new ArrayDeque<>(List.of(replies));
        }

        @Override
        public QuackMessage send(QuackMessage request) {
            var fetch = assertInstanceOf(QuackMessage.FetchRequest.class, request);
            assertEquals(RESULT_UUID, fetch.resultUuid());
            fetches++;
            assertFalse(replies.isEmpty(), "Unexpected extra FETCH");
            Object reply = replies.removeFirst();
            if (reply instanceof RuntimeException failure) throw failure;
            return (QuackMessage) reply;
        }
    }
}

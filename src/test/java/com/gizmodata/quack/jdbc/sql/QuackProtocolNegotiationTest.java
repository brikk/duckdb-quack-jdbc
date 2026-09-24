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

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class QuackProtocolNegotiationTest {
    private static final LogicalType INTEGER = LogicalType.of(LogicalTypeId.INTEGER);
    private static final QuackUri URI = QuackUri.parse("jdbc:quack://example.test");

    @Test
    void legacyHandshakeDoesNotSendV3OnlyFields() {
        List<Long> versions = new ArrayList<>();
        QuackTransport transport = new QuackTransport() {
            @Override public QuackMessage send(QuackMessage request) {
                if (request instanceof QuackMessage.ConnectionRequest connect) {
                    assertEquals(Optional.of(1L), connect.minSupportedQuackVersion());
                    assertEquals(Optional.of(3L), connect.maxSupportedQuackVersion());
                    assertTrue(connect.heartbeatTimeoutSeconds().isEmpty());
                    return connectionResponse(1);
                }
                return new QuackMessage.SuccessResponse(MessageHeader.of(MessageType.SUCCESS_RESPONSE));
            }
            @Override public void setProtocolVersion(long version) { versions.add(version); }
        };
        try (QuackSession session = QuackSession.connect(URI, transport)) {
            assertEquals(1, session.protocolVersion());
        }
        assertEquals(List.of(1L), versions);
    }

    @Test
    void v3HandshakeFetchAndAppendUseTheirOwnMessages() {
        DataChunk chunk = new DataChunk(1, List.of(INTEGER),
                List.of(new DecodedVector.IntVec(INTEGER, new int[]{42}, null)));
        class V3Server implements QuackTransport {
            int handshakes;
            int fetches;
            int sends;
            long version = 1;
            boolean inserting;
            HugeIntParts queryUuid;

            @Override public void setProtocolVersion(long selected) { version = selected; }

            @Override public QuackMessage send(QuackMessage request) {
                if (request instanceof QuackMessage.ConnectionRequest connect) {
                    handshakes++;
                    if (handshakes == 1) {
                        assertTrue(connect.heartbeatTimeoutSeconds().isEmpty());
                        throw new QuackServerException("heartbeat_timeout out of range - must be between 1 and 100");
                    }
                    assertEquals(Optional.of(60L), connect.heartbeatTimeoutSeconds());
                    return connectionResponse(3);
                }
                assertEquals(3, version);
                if (request instanceof QuackMessage.PrepareRequest prepare) {
                    queryUuid = prepare.queryUuid();
                    assertNotEquals(new HugeIntParts(0, 0), queryUuid);
                    if (prepare.sql().startsWith("SELECT * FROM")) {
                        return prepared(queryUuid, List.of("value"), false, List.of());
                    }
                    inserting = prepare.sql().startsWith("INSERT INTO");
                    if (inserting) {
                        assertEquals(Optional.of(0L), prepare.inlineRows());
                        assertTrue(prepare.sql().contains("scan_data_from_quack_client"));
                        return prepared(queryUuid, List.of("Count"), true, List.of());
                    }
                    return prepared(queryUuid, List.of("value"), true, List.of());
                }
                if (request instanceof QuackMessage.FetchRequest fetch) {
                    assertEquals(queryUuid, fetch.resultUuid());
                    fetches++;
                    assertEquals(fetches == 1 || inserting ? 1 : 2, fetch.batchIndex());
                    if (inserting || fetches == 2) {
                        return new QuackMessage.FetchResponse(MessageHeader.of(MessageType.FETCH_RESPONSE),
                                List.of(), Optional.empty(), Optional.of(inserting ? 0L : 1L));
                    }
                    return new QuackMessage.FetchResponse(MessageHeader.of(MessageType.FETCH_RESPONSE),
                            List.of(chunk), Optional.of(1L), Optional.empty());
                }
                if (request instanceof QuackMessage.SendDataRequest data) {
                    sends++;
                    assertTrue(inserting);
                    if (sends == 1) {
                        assertEquals(List.of(chunk), data.chunks());
                        assertEquals(Optional.of(1L), data.batchIndex());
                    } else {
                        assertEquals(List.of(), data.chunks());
                        assertEquals(Optional.of(1L), data.totalBatches());
                    }
                    return new QuackMessage.SendDataResponse(MessageHeader.of(MessageType.SEND_DATA_RESPONSE),
                            Optional.empty());
                }
                if (request instanceof QuackMessage.DisconnectMessage) {
                    return new QuackMessage.SuccessResponse(MessageHeader.of(MessageType.SUCCESS_RESPONSE));
                }
                throw new AssertionError("Unexpected request " + request);
            }
        }
        V3Server server = new V3Server();
        try (QuackSession session = QuackSession.connect(URI, server)) {
            assertEquals(3, session.protocolVersion());
            try (QuackSession.Cursor cursor = session.cursor("SELECT value FROM t")) {
                assertEquals(42, cursor.nextChunk().columns().get(0).getObject(0));
                assertNull(cursor.nextChunk());
            }
            assertEquals(2, server.fetches);
            session.appendChunk("main", "t", chunk);
            assertEquals(2, server.sends);
        }
        assertEquals(2, server.handshakes);
    }

    @Test
    void rejectsUnsupportedSelectedVersionsAtConnect() {
        for (long version : List.of(0L, 2L, 4L)) {
            QuackProtocolException error = assertThrows(QuackProtocolException.class,
                    () -> QuackSession.connect(URI,
                            (QuackTransport) request -> connectionResponse(version)));
            assertTrue(error.getMessage().contains("version " + version));
        }
    }

    private static QuackMessage.ConnectionResponse connectionResponse(long version) {
        return new QuackMessage.ConnectionResponse(
                MessageHeader.of(MessageType.CONNECTION_RESPONSE).withConnectionId("conn"),
                Optional.empty(), Optional.empty(), Optional.of(version),
                version == 3 ? Optional.of(60L) : Optional.empty());
    }

    private static QuackMessage.PrepareResponse prepared(HugeIntParts uuid, List<String> names,
                                                          boolean more, List<DataChunk> chunks) {
        return new QuackMessage.PrepareResponse(MessageHeader.of(MessageType.PREPARE_RESPONSE),
                List.of(INTEGER), names, more, chunks, uuid);
    }
}

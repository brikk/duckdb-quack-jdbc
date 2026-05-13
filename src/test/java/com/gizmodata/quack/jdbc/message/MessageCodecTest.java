package com.gizmodata.quack.jdbc.message;

import com.gizmodata.quack.jdbc.codec.HugeIntParts;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MessageCodecTest {

    @Test
    void connectionRequestRoundTrip() {
        MessageHeader header = MessageHeader.of(MessageType.CONNECTION_REQUEST).withClientQueryId(7);
        QuackMessage.ConnectionRequest original = new QuackMessage.ConnectionRequest(
                header,
                Optional.of("tok"),
                Optional.of("test/0.1"),
                Optional.of("linux"),
                Optional.of(1L),
                Optional.of(1L));
        byte[] bytes = MessageCodec.encode(original);
        QuackMessage decoded = MessageCodec.decode(bytes);
        assertTrue(decoded instanceof QuackMessage.ConnectionRequest);
        QuackMessage.ConnectionRequest c = (QuackMessage.ConnectionRequest) decoded;
        assertEquals("tok", c.authString().orElse(""));
        assertEquals("test/0.1", c.clientDuckdbVersion().orElse(""));
        assertEquals("linux", c.clientPlatform().orElse(""));
        assertEquals(Optional.of(7L), c.header().clientQueryId());
    }

    @Test
    void prepareRequestRoundTrip() {
        QuackMessage.PrepareRequest original = new QuackMessage.PrepareRequest(
                MessageHeader.of(MessageType.PREPARE_REQUEST).withConnectionId("CONN").withClientQueryId(1),
                "SELECT 1");
        byte[] bytes = MessageCodec.encode(original);
        QuackMessage decoded = MessageCodec.decode(bytes);
        assertTrue(decoded instanceof QuackMessage.PrepareRequest);
        QuackMessage.PrepareRequest p = (QuackMessage.PrepareRequest) decoded;
        assertEquals("SELECT 1", p.sql());
        assertEquals("CONN", p.header().connectionId().orElse(""));
    }

    @Test
    void fetchRequestRoundTrip() {
        HugeIntParts uuid = new HugeIntParts(0x1122334455667788L, 0x99AABBCCDDEEFF00L);
        QuackMessage.FetchRequest original = new QuackMessage.FetchRequest(
                MessageHeader.of(MessageType.FETCH_REQUEST).withConnectionId("c1").withClientQueryId(2),
                uuid);
        byte[] bytes = MessageCodec.encode(original);
        QuackMessage decoded = MessageCodec.decode(bytes);
        assertTrue(decoded instanceof QuackMessage.FetchRequest);
        assertEquals(uuid, ((QuackMessage.FetchRequest) decoded).resultUuid());
    }

    @Test
    void errorResponseRoundTrip() {
        QuackMessage.ErrorResponse original = new QuackMessage.ErrorResponse(
                MessageHeader.of(MessageType.ERROR_RESPONSE).withClientQueryId(99),
                "out of cheese");
        byte[] bytes = MessageCodec.encode(original);
        QuackMessage decoded = MessageCodec.decode(bytes);
        assertTrue(decoded instanceof QuackMessage.ErrorResponse);
        assertEquals("out of cheese", ((QuackMessage.ErrorResponse) decoded).message());
    }

    @Test
    void disconnectAndSuccessRoundTrip() {
        QuackMessage.DisconnectMessage d = new QuackMessage.DisconnectMessage(
                MessageHeader.of(MessageType.DISCONNECT_MESSAGE).withConnectionId("c").withClientQueryId(1));
        QuackMessage decoded = MessageCodec.decode(MessageCodec.encode(d));
        assertTrue(decoded instanceof QuackMessage.DisconnectMessage);

        QuackMessage.SuccessResponse s = new QuackMessage.SuccessResponse(
                MessageHeader.of(MessageType.SUCCESS_RESPONSE).withClientQueryId(1));
        QuackMessage sd = MessageCodec.decode(MessageCodec.encode(s));
        assertTrue(sd instanceof QuackMessage.SuccessResponse);
    }

    @Test
    void prepareResponseWithEmptyResultsDecodes() {
        QuackMessage.PrepareResponse original = new QuackMessage.PrepareResponse(
                MessageHeader.of(MessageType.PREPARE_RESPONSE).withClientQueryId(1),
                List.of(), List.of(), false, List.of(),
                new HugeIntParts(0L, 0L));
        QuackMessage decoded = MessageCodec.decode(MessageCodec.encode(original));
        assertNotNull(decoded);
        assertTrue(decoded instanceof QuackMessage.PrepareResponse);
        QuackMessage.PrepareResponse pr = (QuackMessage.PrepareResponse) decoded;
        assertEquals(0, pr.resultTypes().size());
        assertEquals(0, pr.resultNames().size());
    }
}

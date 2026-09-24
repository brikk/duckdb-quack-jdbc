package com.gizmodata.quack.jdbc.message;

import com.gizmodata.quack.jdbc.QuackProtocolException;
import com.gizmodata.quack.jdbc.codec.BinaryWriter;
import com.gizmodata.quack.jdbc.codec.DecodeLimits;
import com.gizmodata.quack.jdbc.codec.HugeIntParts;
import com.gizmodata.quack.jdbc.type.LogicalType;
import com.gizmodata.quack.jdbc.type.LogicalTypeId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

class VersionedMessageCodecTest {
    private static final LogicalType VARCHAR = LogicalType.of(LogicalTypeId.VARCHAR);

    @Test
    void v3RawChunksAndNewStringLayoutRoundTrip() {
        DataChunk chunk = new DataChunk(3, List.of(VARCHAR), List.of(
                new DecodedVector.ObjectVec(VARCHAR, new Object[]{"hello", null, "é"})));
        var response = new QuackMessage.FetchResponse(MessageHeader.of(MessageType.FETCH_RESPONSE),
                List.of(chunk), Optional.of(1L), Optional.empty());
        byte[] bytes = MessageCodec.encode(response, 3);
        var decoded = assertInstanceOf(QuackMessage.FetchResponse.class,
                MessageCodec.decode(bytes, DecodeLimits.DEFAULT, 3));
        assertEquals(Optional.of(1L), decoded.batchIndex());
        assertEquals(1, decoded.results().size());
        assertEquals("hello", decoded.results().get(0).columns().get(0).getObject(0));
        assertNull(decoded.results().get(0).columns().get(0).getObject(1));
        assertEquals("é", decoded.results().get(0).columns().get(0).getObject(2));
        assertThrows(QuackProtocolException.class, () -> MessageCodec.decode(bytes));

        var data = new QuackMessage.SendDataRequest(
                MessageHeader.of(MessageType.SEND_DATA_REQUEST).withConnectionId("conn"),
                "stream", List.of(chunk), Optional.of(1L), Optional.empty());
        var sent = assertInstanceOf(QuackMessage.SendDataRequest.class,
                MessageCodec.decode(MessageCodec.encode(data, 3), DecodeLimits.DEFAULT, 3));
        assertEquals("stream", sent.streamId());
        assertEquals("é", sent.chunks().get(0).columns().get(0).getObject(2));

        var terminal = new QuackMessage.FetchResponse(MessageHeader.of(MessageType.FETCH_RESPONSE),
                List.of(), Optional.empty(), Optional.of(1L));
        assertEquals(terminal, MessageCodec.decode(MessageCodec.encode(terminal, 3), DecodeLimits.DEFAULT, 3));
    }

    @Test
    void v3TypedErrorCanBeDecodedBeforeVersionSelection() {
        BinaryWriter wire = new BinaryWriter();
        wire.writeObject(header -> {
            header.writeField(1, () -> header.writeUleb(100));
            header.writeField(3, () -> header.writeUleb(-1));
        });
        wire.writeObject(body -> {
            body.writeField(1, () -> body.writeString("heartbeat_timeout out of range"));
            body.writeField(2, () -> body.writeString("Invalid Input"));
            body.writeField(3, () -> body.writeList(List.of("detail"), (entry, i) ->
                    body.writeObject(pair -> {
                        pair.writeField(0, () -> pair.writeString(entry));
                        pair.writeField(1, () -> pair.writeString("value"));
                    })));
            body.writeField(4, () -> body.writeBool(true));
        });
        var error = assertInstanceOf(QuackMessage.ErrorResponse.class,
                MessageCodec.decode(wire.toByteArray()));
        assertEquals("heartbeat_timeout out of range", error.message());
    }

    @Test
    void v3PrepareAndFetchCarryExplicitQueryAndBatchIds() {
        HugeIntParts uuid = new HugeIntParts(4, 5);
        var prepare = new QuackMessage.PrepareRequest(MessageHeader.of(MessageType.PREPARE_REQUEST),
                "SELECT 1", uuid, Optional.of(0L));
        assertEquals(prepare, MessageCodec.decode(MessageCodec.encode(prepare, 3), DecodeLimits.DEFAULT, 3));
        var fetch = new QuackMessage.FetchRequest(MessageHeader.of(MessageType.FETCH_REQUEST), uuid, 2, 1);
        assertEquals(fetch, MessageCodec.decode(MessageCodec.encode(fetch, 3), DecodeLimits.DEFAULT, 3));
    }
}

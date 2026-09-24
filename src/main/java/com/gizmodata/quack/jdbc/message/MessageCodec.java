package com.gizmodata.quack.jdbc.message;

import com.gizmodata.quack.jdbc.QuackProtocolException;
import com.gizmodata.quack.jdbc.codec.BinaryReader;
import com.gizmodata.quack.jdbc.codec.BinaryWriter;
import com.gizmodata.quack.jdbc.codec.DecodeLimits;
import com.gizmodata.quack.jdbc.codec.HugeIntParts;
import com.gizmodata.quack.jdbc.codec.QuackConstants;
import com.gizmodata.quack.jdbc.type.LogicalType;
import com.gizmodata.quack.jdbc.type.LogicalTypeCodec;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class MessageCodec {

    private MessageCodec() {
    }

    public static byte[] encode(QuackMessage message) {
        return encode(message, QuackConstants.QUACK_VERSION);
    }

    public static byte[] encode(QuackMessage message, long protocolVersion) {
        BinaryWriter writer = new BinaryWriter(256, protocolVersion);
        encodeHeader(writer, message.header(), message);
        encodeBody(writer, message, protocolVersion);
        return writer.toByteArray();
    }

    public static QuackMessage decode(byte[] bytes) {
        return decode(bytes, DecodeLimits.DEFAULT);
    }

    public static QuackMessage decode(byte[] bytes, DecodeLimits limits) {
        return decode(bytes, limits, QuackConstants.QUACK_VERSION);
    }

    public static QuackMessage decode(byte[] bytes, DecodeLimits limits, long protocolVersion) {
        BinaryReader reader = new BinaryReader(bytes, limits, protocolVersion);
        DecodedHeader decoded = decodeHeader(reader);
        QuackMessage message = decodeBody(reader, decoded, protocolVersion);
        reader.assertEof();
        return message;
    }

    private static void encodeHeader(BinaryWriter writer, MessageHeader header, QuackMessage message) {
        writer.writeObject(obj -> {
            obj.writeField(1, () -> obj.writeUleb(messageType(message).wireId()));
            header.connectionId().ifPresent(id ->
                    obj.writeField(2, () -> obj.writeString(id)));
            obj.writeField(3, () -> obj.writeUleb(
                    header.clientQueryId().orElse(QuackConstants.OPTIONAL_INDEX_INVALID)));
        });
    }

    private static DecodedHeader decodeHeader(BinaryReader reader) {
        return reader.readObject(() -> {
            MessageType type = MessageType.fromWireId(
                    reader.readRequiredField(1, reader::readUlebInt), reader.protocolVersion());
            Optional<String> connectionId = Optional.ofNullable(
                    reader.readOptionalField(2, reader::readString, null));
            if (connectionId.isPresent() && connectionId.get().isEmpty()) {
                connectionId = Optional.empty();
            }
            long rawQueryId = reader.readRequiredField(3, reader::readUlebLong);
            Optional<Long> clientQueryId = rawQueryId == QuackConstants.OPTIONAL_INDEX_INVALID
                    ? Optional.empty() : Optional.of(rawQueryId);
            return new DecodedHeader(new MessageHeader(type, connectionId, clientQueryId));
        });
    }

    private static MessageType messageType(QuackMessage message) {
        if (message instanceof QuackMessage.ConnectionRequest) return MessageType.CONNECTION_REQUEST;
        if (message instanceof QuackMessage.ConnectionResponse) return MessageType.CONNECTION_RESPONSE;
        if (message instanceof QuackMessage.PrepareRequest) return MessageType.PREPARE_REQUEST;
        if (message instanceof QuackMessage.PrepareResponse) return MessageType.PREPARE_RESPONSE;
        if (message instanceof QuackMessage.FetchRequest) return MessageType.FETCH_REQUEST;
        if (message instanceof QuackMessage.FetchResponse) return MessageType.FETCH_RESPONSE;
        if (message instanceof QuackMessage.AppendRequest) return MessageType.APPEND_REQUEST;
        if (message instanceof QuackMessage.SuccessResponse) return MessageType.SUCCESS_RESPONSE;
        if (message instanceof QuackMessage.DisconnectMessage) return MessageType.DISCONNECT_MESSAGE;
        if (message instanceof QuackMessage.ErrorResponse) return MessageType.ERROR_RESPONSE;
        if (message instanceof QuackMessage.HeartbeatRequest) return MessageType.HEARTBEAT_REQUEST;
        if (message instanceof QuackMessage.SendDataRequest) return MessageType.SEND_DATA_REQUEST;
        if (message instanceof QuackMessage.SendDataResponse) return MessageType.SEND_DATA_RESPONSE;
        throw new QuackProtocolException("Unknown message: " + message.getClass());
    }

    private static void encodeBody(BinaryWriter writer, QuackMessage message, long protocolVersion) {
        if (message instanceof QuackMessage.ConnectionRequest m) {
            writer.writeObject(obj -> {
                writeOptionalString(obj, 1, m.authString());
                writeOptionalString(obj, 2, m.clientDuckdbVersion());
                writeOptionalString(obj, 3, m.clientPlatform());
                writeOptionalIndexDefaultZero(obj, 4, m.minSupportedQuackVersion());
                writeOptionalIndexDefaultZero(obj, 5, m.maxSupportedQuackVersion());
                writeOptionalString(obj, 6, m.clientId());
                writeOptionalIndexDefaultZero(obj, 7, m.heartbeatTimeoutSeconds());
            });
            return;
        }
        if (message instanceof QuackMessage.ConnectionResponse m) {
            writer.writeObject(obj -> {
                writeOptionalString(obj, 1, m.serverDuckdbVersion());
                writeOptionalString(obj, 2, m.serverPlatform());
                m.quackVersion().ifPresent(v -> obj.writeField(3, () -> obj.writeUleb(v)));
                writeOptionalIndexDefaultZero(obj, 4, m.heartbeatTimeoutSeconds());
            });
            return;
        }
        if (message instanceof QuackMessage.PrepareRequest m) {
            writer.writeObject(obj -> {
                if (m.sql() != null && !m.sql().isEmpty()) {
                    obj.writeField(1, () -> obj.writeString(m.sql()));
                }
                if (protocolVersion == 3) {
                    obj.writeField(2, () -> obj.writeHugeInt(m.queryUuid()));
                    m.inlineRows().ifPresent(v -> obj.writeField(3, () -> obj.writeUleb(v)));
                }
            });
            return;
        }
        if (message instanceof QuackMessage.PrepareResponse m) {
            writer.writeObject(obj -> {
                if (!m.resultTypes().isEmpty()) {
                    obj.writeField(1, () -> obj.writeList(m.resultTypes(),
                            (t, i) -> LogicalTypeCodec.encode(obj, t)));
                }
                if (!m.resultNames().isEmpty()) {
                    obj.writeField(2, () -> obj.writeList(m.resultNames(),
                            (n, i) -> obj.writeString(n)));
                }
                if (m.needsMoreFetch()) {
                    obj.writeField(3, () -> obj.writeBool(true));
                }
                if (!m.results().isEmpty()) {
                    obj.writeField(4, () -> writeChunkPointerList(obj, m.results()));
                }
                obj.writeField(5, () -> obj.writeHugeInt(m.resultUuid()));
            });
            return;
        }
        if (message instanceof QuackMessage.FetchRequest m) {
            writer.writeObject(obj -> {
                obj.writeField(1, () -> obj.writeHugeInt(m.resultUuid()));
                if (protocolVersion == 3) {
                    if (m.batchIndex() == 0) throw new QuackProtocolException("v3 FETCH requires a batch index");
                    obj.writeField(2, () -> obj.writeUleb(m.batchIndex()));
                    if (m.ackIndex() != 0) obj.writeField(3, () -> obj.writeUleb(m.ackIndex()));
                }
            });
            return;
        }
        if (message instanceof QuackMessage.FetchResponse m) {
            if (protocolVersion == 3) {
                writer.writeObject(obj -> {
                    if (!m.results().isEmpty()) obj.writeField(1, () -> obj.writeUleb(m.results().size()));
                    m.totalBatches().ifPresent(v -> obj.writeField(2, () -> obj.writeUleb(v)));
                    m.batchIndex().ifPresent(v -> obj.writeField(3, () -> obj.writeUleb(v)));
                });
                for (DataChunk chunk : m.results()) VectorCodec.encodeDataChunk(writer, chunk);
                return;
            }
            writer.writeObject(obj -> {
                if (!m.results().isEmpty()) {
                    obj.writeField(1, () -> writeChunkPointerList(obj, m.results()));
                }
                obj.writeField(2, () -> obj.writeUleb(
                        m.batchIndex().orElse(QuackConstants.OPTIONAL_INDEX_INVALID)));
            });
            return;
        }
        if (message instanceof QuackMessage.AppendRequest m) {
            writer.writeObject(obj -> {
                writeOptionalString(obj, 1, m.schemaName());
                if (m.tableName() != null && !m.tableName().isEmpty()) {
                    obj.writeField(2, () -> obj.writeString(m.tableName()));
                }
                obj.writeField(3, () -> obj.writeNullable(m.appendChunk(),
                        chunk -> VectorCodec.encodeDataChunkWrapper(obj, chunk)));
            });
            return;
        }
        if (message instanceof QuackMessage.SendDataRequest m) {
            if (protocolVersion != 3) throw new QuackProtocolException("SEND_DATA requires Quack v3");
            writer.writeObject(obj -> {
                writeOptionalString(obj, 1, Optional.of(m.streamId()));
                if (!m.chunks().isEmpty()) obj.writeField(2, () -> obj.writeUleb(m.chunks().size()));
                m.totalBatches().ifPresent(v -> obj.writeField(3, () -> obj.writeUleb(v)));
                m.batchIndex().ifPresent(v -> obj.writeField(4, () -> obj.writeUleb(v)));
            });
            for (DataChunk chunk : m.chunks()) VectorCodec.encodeDataChunk(writer, chunk);
            return;
        }
        if (message instanceof QuackMessage.SendDataResponse m) {
            writer.writeObject(obj -> obj.writeField(1, () -> obj.writeUleb(
                    m.acceptBudget().orElse(QuackConstants.OPTIONAL_INDEX_INVALID))));
            return;
        }
        if (message instanceof QuackMessage.SuccessResponse
                || message instanceof QuackMessage.DisconnectMessage
                || message instanceof QuackMessage.HeartbeatRequest) {
            writer.writeObject(obj -> {});
            return;
        }
        if (message instanceof QuackMessage.ErrorResponse m) {
            writer.writeObject(obj -> {
                if (m.message() != null && !m.message().isEmpty()) {
                    obj.writeField(1, () -> obj.writeString(m.message()));
                }
            });
            return;
        }
        throw new QuackProtocolException("Cannot encode unsupported message type "
                + message.getClass().getSimpleName());
    }

    private static QuackMessage decodeBody(BinaryReader reader, DecodedHeader decoded, long protocolVersion) {
        MessageHeader header = decoded.header();
        return switch (header.type()) {
            case CONNECTION_REQUEST -> reader.readObject(() -> new QuackMessage.ConnectionRequest(
                    header,
                    optionalString(reader, 1),
                    optionalString(reader, 2),
                    optionalString(reader, 3),
                    Optional.of(reader.readOptionalField(4, reader::readUlebLong, 0L)),
                    Optional.of(reader.readOptionalField(5, reader::readUlebLong, 0L)),
                    optionalString(reader, 6),
                    Optional.ofNullable(reader.readOptionalField(7, reader::readUlebLong, null))));
            case CONNECTION_RESPONSE -> reader.readObject(() -> new QuackMessage.ConnectionResponse(
                    header,
                    optionalString(reader, 1),
                    optionalString(reader, 2),
                    Optional.ofNullable(reader.readOptionalField(3, reader::readUlebLong, null)),
                    Optional.ofNullable(reader.readOptionalField(4, reader::readUlebLong, null))));
            case PREPARE_REQUEST -> reader.readObject(() -> new QuackMessage.PrepareRequest(
                    header,
                    reader.readOptionalField(1, reader::readString, ""),
                    protocolVersion == 3 ? reader.readRequiredField(2, reader::readHugeInt)
                            : new HugeIntParts(0, 0),
                    protocolVersion == 3 ? optionalIndex(reader, 3) : Optional.empty()));
            case PREPARE_RESPONSE -> reader.readObject(() -> {
                List<LogicalType> resultTypes = reader.readOptionalField(1,
                        () -> reader.readList(i -> LogicalTypeCodec.decode(reader)), List.of());
                List<String> resultNames = reader.readOptionalField(2,
                        () -> reader.readList(i -> reader.readString()), List.of());
                boolean needsMoreFetch = reader.readOptionalField(3, reader::readBool, false);
                List<DataChunk> results = reader.readOptionalField(4,
                        () -> readChunkPointerList(reader), List.of());
                HugeIntParts resultUuid = reader.readRequiredField(5, reader::readHugeInt);
                return new QuackMessage.PrepareResponse(header, resultTypes, resultNames,
                        needsMoreFetch, results, resultUuid);
            });
            case FETCH_REQUEST -> reader.readObject(() -> new QuackMessage.FetchRequest(
                    header, reader.readRequiredField(1, reader::readHugeInt),
                    protocolVersion == 3 ? reader.readOptionalField(2, reader::readUlebLong, 0L) : 0,
                    protocolVersion == 3 ? reader.readOptionalField(3, reader::readUlebLong, 0L) : 0));
            case FETCH_RESPONSE -> {
                if (protocolVersion == 3) {
                    V3FetchBody body = reader.readObject(() -> new V3FetchBody(
                            reader.readOptionalField(1, reader::readUlebInt, 0),
                            optionalIndex(reader, 2), optionalIndex(reader, 3)));
                    // The raw blob follows the body's end-of-object marker.
                    if (body.chunkCount() > reader.remaining() / 6) {
                        throw new QuackProtocolException("FETCH chunk count exceeds response size");
                    }
                    List<DataChunk> chunks = new ArrayList<>();
                    for (int i = 0; i < body.chunkCount(); i++) chunks.add(VectorCodec.decodeDataChunk(reader));
                    yield new QuackMessage.FetchResponse(header, chunks, body.batchIndex(), body.totalBatches());
                }
                yield reader.readObject(() -> {
                    List<DataChunk> results = reader.readOptionalField(1,
                            () -> readChunkPointerList(reader), List.of());
                    long batchIndex = reader.readRequiredField(2, reader::readUlebLong);
                    Optional<Long> batchOpt = batchIndex == QuackConstants.OPTIONAL_INDEX_INVALID
                            ? Optional.empty() : Optional.of(batchIndex);
                    return new QuackMessage.FetchResponse(header, results, batchOpt);
                });
            }
            case APPEND_REQUEST -> reader.readObject(() -> {
                Optional<String> schemaName = optionalString(reader, 1);
                String tableName = reader.readOptionalField(2, reader::readString, "");
                DataChunk chunk = reader.readOptionalField(3,
                        () -> reader.readNullable(() -> VectorCodec.decodeDataChunkWrapper(reader)),
                        null);
                if (chunk == null) {
                    throw new QuackProtocolException("APPEND_REQUEST is missing append_chunk");
                }
                return new QuackMessage.AppendRequest(header, schemaName, tableName, chunk);
            });
            case SEND_DATA_REQUEST -> {
                V3SendBody body = reader.readObject(() -> new V3SendBody(
                        reader.readOptionalField(1, reader::readString, ""),
                        reader.readOptionalField(2, reader::readUlebInt, 0),
                        optionalIndex(reader, 3), optionalIndex(reader, 4)));
                if (body.chunkCount() > reader.remaining() / 6) {
                    throw new QuackProtocolException("SEND_DATA chunk count exceeds request size");
                }
                List<DataChunk> chunks = new ArrayList<>();
                for (int i = 0; i < body.chunkCount(); i++) chunks.add(VectorCodec.decodeDataChunk(reader));
                yield new QuackMessage.SendDataRequest(header, body.streamId(), chunks,
                        body.batchIndex(), body.totalBatches());
            }
            case SEND_DATA_RESPONSE -> reader.readObject(() -> new QuackMessage.SendDataResponse(
                    header, optionalIndex(reader, 1)));
            case SUCCESS_RESPONSE -> reader.readObject(() -> new QuackMessage.SuccessResponse(header));
            case DISCONNECT_MESSAGE -> reader.readObject(() -> new QuackMessage.DisconnectMessage(header));
            case HEARTBEAT_REQUEST -> reader.readObject(() -> new QuackMessage.HeartbeatRequest(header));
            case ERROR_RESPONSE -> reader.readObject(() -> {
                String message = reader.readOptionalField(1, reader::readString, "");
                // v3 can report an error before version negotiation (notably
                // the missing heartbeat lease). Consume its typed diagnostics
                // even when this connection is still decoding as v1.
                reader.readOptionalField(2, reader::readString, "");
                reader.readOptionalField(3, () -> reader.readList(i -> {
                    return reader.readObject(() -> {
                        reader.readRequiredField(0, reader::readString);
                        reader.readRequiredField(1, reader::readString);
                        return 0;
                    });
                }), List.of());
                reader.readOptionalField(4, reader::readBool, false);
                return new QuackMessage.ErrorResponse(header, message);
            });
            case INVALID -> throw new QuackProtocolException(
                    "Cannot decode message type " + header.type());
        };
    }

    private static Optional<String> optionalString(BinaryReader reader, int fieldId) {
        return Optional.ofNullable(reader.readOptionalField(fieldId, reader::readString, null));
    }

    private static Optional<Long> optionalIndex(BinaryReader reader, int fieldId) {
        long raw = reader.readOptionalField(fieldId, reader::readUlebLong, QuackConstants.OPTIONAL_INDEX_INVALID);
        return raw == QuackConstants.OPTIONAL_INDEX_INVALID ? Optional.empty() : Optional.of(raw);
    }

    private static void writeOptionalString(BinaryWriter writer, int fieldId, Optional<String> value) {
        value.filter(s -> !s.isEmpty()).ifPresent(v ->
                writer.writeField(fieldId, () -> writer.writeString(v)));
    }

    private static void writeOptionalIndexDefaultZero(BinaryWriter writer, int fieldId, Optional<Long> value) {
        value.filter(v -> v != 0L).ifPresent(v ->
                writer.writeField(fieldId, () -> writer.writeUleb(v)));
    }

    private static List<DataChunk> readChunkPointerList(BinaryReader reader) {
        return reader.readList(i -> {
            DataChunk chunk = reader.readNullable(() -> VectorCodec.decodeDataChunkWrapper(reader));
            if (chunk == null) {
                throw new QuackProtocolException("Encountered null DataChunk pointer in result list");
            }
            return chunk;
        });
    }

    private static void writeChunkPointerList(BinaryWriter writer, List<DataChunk> chunks) {
        writer.writeList(chunks, (chunk, i) -> writer.writeNullable(chunk,
                value -> VectorCodec.encodeDataChunkWrapper(writer, value)));
    }

    private record DecodedHeader(MessageHeader header) {}

    private record V3FetchBody(int chunkCount, Optional<Long> totalBatches, Optional<Long> batchIndex) {}

    private record V3SendBody(String streamId, int chunkCount,
                              Optional<Long> totalBatches, Optional<Long> batchIndex) {}

    /** Just so unused warnings stay quiet during early iteration. */
    @SuppressWarnings("unused")
    private static final List<Object> UNUSED = new ArrayList<>();
}

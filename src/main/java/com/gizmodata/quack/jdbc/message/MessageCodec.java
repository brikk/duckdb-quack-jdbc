package com.gizmodata.quack.jdbc.message;

import com.gizmodata.quack.jdbc.QuackProtocolException;
import com.gizmodata.quack.jdbc.codec.BinaryReader;
import com.gizmodata.quack.jdbc.codec.BinaryWriter;
import com.gizmodata.quack.jdbc.codec.DecodeLimits;
import com.gizmodata.quack.jdbc.codec.HugeIntParts;
import com.gizmodata.quack.jdbc.codec.QuackConstants;
import com.gizmodata.quack.jdbc.codec.WireField;
import com.gizmodata.quack.jdbc.type.LogicalType;
import com.gizmodata.quack.jdbc.type.LogicalTypeCodec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
            obj.writeField(MessageFields.Header.MESSAGE_TYPE, () -> obj.writeUleb(messageType(message).wireId()));
            header.connectionId().ifPresent(id ->
                    obj.writeField(MessageFields.Header.CONNECTION_ID, () -> obj.writeString(id)));
            obj.writeField(MessageFields.Header.CLIENT_QUERY_ID, () -> obj.writeUleb(
                    header.clientQueryId().orElse(QuackConstants.OPTIONAL_INDEX_INVALID)));
        });
    }

    private static DecodedHeader decodeHeader(BinaryReader reader) {
        return reader.readObject(() -> {
            MessageType type = MessageType.fromWireId(
                    reader.readRequiredField(MessageFields.Header.MESSAGE_TYPE, reader::readUlebInt), reader.protocolVersion());
            Optional<String> connectionId = Optional.ofNullable(
                    reader.readOptionalField(MessageFields.Header.CONNECTION_ID, reader::readString, null));
            if (connectionId.isPresent() && connectionId.get().isEmpty()) {
                connectionId = Optional.empty();
            }
            long rawQueryId = reader.readRequiredField(MessageFields.Header.CLIENT_QUERY_ID, reader::readUlebLong);
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
        if (message instanceof QuackMessage.CancelRequest) return MessageType.CANCEL_REQUEST;
        if (message instanceof QuackMessage.ErrorResponse) return MessageType.ERROR_RESPONSE;
        if (message instanceof QuackMessage.HeartbeatRequest) return MessageType.HEARTBEAT_REQUEST;
        if (message instanceof QuackMessage.SendDataRequest) return MessageType.SEND_DATA_REQUEST;
        if (message instanceof QuackMessage.SendDataResponse) return MessageType.SEND_DATA_RESPONSE;
        throw new QuackProtocolException("Unknown message: " + message.getClass());
    }

    private static void encodeBody(BinaryWriter writer, QuackMessage message, long protocolVersion) {
        if (message instanceof QuackMessage.ConnectionRequest m) {
            writer.writeObject(obj -> {
                writeOptionalString(obj, MessageFields.ConnectionRequest.AUTH_STRING, m.authString());
                writeOptionalString(obj, MessageFields.ConnectionRequest.CLIENT_DUCKDB_VERSION, m.clientDuckdbVersion());
                writeOptionalString(obj, MessageFields.ConnectionRequest.CLIENT_PLATFORM, m.clientPlatform());
                writeOptionalIndexDefaultZero(obj, MessageFields.ConnectionRequest.MIN_SUPPORTED_QUACK_VERSION, m.minSupportedQuackVersion());
                writeOptionalIndexDefaultZero(obj, MessageFields.ConnectionRequest.MAX_SUPPORTED_QUACK_VERSION, m.maxSupportedQuackVersion());
                writeOptionalString(obj, MessageFields.ConnectionRequest.CLIENT_ID, m.clientId());
                writeOptionalIndexDefaultZero(obj, MessageFields.ConnectionRequest.HEARTBEAT_TIMEOUT_SECONDS, m.heartbeatTimeoutSeconds());
            });
            return;
        }
        if (message instanceof QuackMessage.ConnectionResponse m) {
            writer.writeObject(obj -> {
                writeOptionalString(obj, MessageFields.ConnectionResponse.SERVER_DUCKDB_VERSION, m.serverDuckdbVersion());
                writeOptionalString(obj, MessageFields.ConnectionResponse.SERVER_PLATFORM, m.serverPlatform());
                m.quackVersion().ifPresent(v -> obj.writeField(MessageFields.ConnectionResponse.QUACK_VERSION, () -> obj.writeUleb(v)));
                writeOptionalIndexDefaultZero(obj, MessageFields.ConnectionResponse.HEARTBEAT_TIMEOUT_SECONDS, m.heartbeatTimeoutSeconds());
            });
            return;
        }
        if (message instanceof QuackMessage.PrepareRequest m) {
            writer.writeObject(obj -> {
                if (m.sql() != null && !m.sql().isEmpty()) {
                    obj.writeField(MessageFields.PrepareRequest.SQL, () -> obj.writeString(m.sql()));
                }
                if (protocolVersion == 3) {
                    obj.writeField(MessageFields.PrepareRequest.QUERY_UUID, () -> obj.writeHugeInt(m.queryUuid()));
                    m.inlineRows().ifPresent(v -> obj.writeField(MessageFields.PrepareRequest.INLINE_ROWS, () -> obj.writeUleb(v)));
                }
            });
            return;
        }
        if (message instanceof QuackMessage.PrepareResponse m) {
            writer.writeObject(obj -> {
                if (!m.resultTypes().isEmpty()) {
                    obj.writeField(MessageFields.PrepareResponse.RESULT_TYPES, () -> obj.writeList(m.resultTypes(),
                            (t, i) -> LogicalTypeCodec.encode(obj, t)));
                }
                if (!m.resultNames().isEmpty()) {
                    obj.writeField(MessageFields.PrepareResponse.RESULT_NAMES, () -> obj.writeList(m.resultNames(),
                            (n, i) -> obj.writeString(n)));
                }
                if (m.needsMoreFetch()) {
                    obj.writeField(MessageFields.PrepareResponse.NEEDS_MORE_FETCH, () -> obj.writeBool(true));
                }
                if (!m.results().isEmpty()) {
                    obj.writeField(MessageFields.PrepareResponse.RESULTS, () -> writeChunkPointerList(obj, m.results()));
                }
                obj.writeField(MessageFields.PrepareResponse.RESULT_UUID, () -> obj.writeHugeInt(m.resultUuid()));
            });
            return;
        }
        if (message instanceof QuackMessage.FetchRequest m) {
            writer.writeObject(obj -> {
                obj.writeField(MessageFields.FetchRequest.RESULT_UUID, () -> obj.writeHugeInt(m.resultUuid()));
                if (protocolVersion == 3) {
                    if (m.batchIndex() == 0) throw new QuackProtocolException("v3 FETCH requires a batch index");
                    obj.writeField(MessageFields.FetchRequest.BATCH_INDEX, () -> obj.writeUleb(m.batchIndex()));
                    if (m.ackIndex() != 0) obj.writeField(MessageFields.FetchRequest.ACK_INDEX, () -> obj.writeUleb(m.ackIndex()));
                }
            });
            return;
        }
        if (message instanceof QuackMessage.FetchResponse m) {
            if (protocolVersion == 3) {
                writer.writeObject(obj -> {
                    if (!m.results().isEmpty()) obj.writeField(MessageFields.FetchResponseV3.CHUNK_COUNT, () -> obj.writeUleb(m.results().size()));
                    m.totalBatches().ifPresent(v -> obj.writeField(MessageFields.FetchResponseV3.TOTAL_BATCHES, () -> obj.writeUleb(v)));
                    m.batchIndex().ifPresent(v -> obj.writeField(MessageFields.FetchResponseV3.BATCH_INDEX, () -> obj.writeUleb(v)));
                });
                for (DataChunk chunk : m.results()) VectorCodec.encodeDataChunk(writer, chunk);
                return;
            }
            writer.writeObject(obj -> {
                if (!m.results().isEmpty()) {
                    obj.writeField(MessageFields.FetchResponse.RESULTS, () -> writeChunkPointerList(obj, m.results()));
                }
                obj.writeField(MessageFields.FetchResponse.BATCH_INDEX, () -> obj.writeUleb(
                        m.batchIndex().orElse(QuackConstants.OPTIONAL_INDEX_INVALID)));
            });
            return;
        }
        if (message instanceof QuackMessage.AppendRequest m) {
            writer.writeObject(obj -> {
                writeOptionalString(obj, MessageFields.AppendRequest.SCHEMA_NAME, m.schemaName());
                if (m.tableName() != null && !m.tableName().isEmpty()) {
                    obj.writeField(MessageFields.AppendRequest.TABLE_NAME, () -> obj.writeString(m.tableName()));
                }
                obj.writeField(MessageFields.AppendRequest.APPEND_CHUNK, () -> obj.writeNullable(m.appendChunk(),
                        chunk -> VectorCodec.encodeDataChunkWrapper(obj, chunk)));
            });
            return;
        }
        if (message instanceof QuackMessage.SendDataRequest m) {
            if (protocolVersion != 3) throw new QuackProtocolException("SEND_DATA requires Quack v3");
            writer.writeObject(obj -> {
                writeOptionalString(obj, MessageFields.SendDataRequest.STREAM_ID, Optional.of(m.streamId()));
                if (!m.chunks().isEmpty()) obj.writeField(MessageFields.SendDataRequest.CHUNK_COUNT, () -> obj.writeUleb(m.chunks().size()));
                m.totalBatches().ifPresent(v -> obj.writeField(MessageFields.SendDataRequest.TOTAL_BATCHES, () -> obj.writeUleb(v)));
                m.batchIndex().ifPresent(v -> obj.writeField(MessageFields.SendDataRequest.BATCH_INDEX, () -> obj.writeUleb(v)));
            });
            for (DataChunk chunk : m.chunks()) VectorCodec.encodeDataChunk(writer, chunk);
            return;
        }
        if (message instanceof QuackMessage.SendDataResponse m) {
            writer.writeObject(obj -> obj.writeField(MessageFields.SendDataResponse.ACCEPT_BUDGET, () -> obj.writeUleb(
                    m.acceptBudget().orElse(QuackConstants.OPTIONAL_INDEX_INVALID))));
            return;
        }
        if (message instanceof QuackMessage.CancelRequest m) {
            if (protocolVersion != 3) throw new QuackProtocolException("CANCEL requires Quack v3");
            writer.writeObject(obj -> obj.writeField(MessageFields.CancelRequest.QUERY_UUID, () -> obj.writeHugeInt(m.queryUuid())));
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
                    obj.writeField(MessageFields.ErrorResponse.MESSAGE, () -> obj.writeString(m.message()));
                }
                if (protocolVersion == 3) {
                    writeOptionalString(obj, MessageFields.ErrorResponse.EXCEPTION_TYPE, Optional.of(m.exceptionType()));
                    if (!m.extraInfo().isEmpty()) {
                        obj.writeField(MessageFields.ErrorResponse.EXTRA_INFO, () -> obj.writeList(new ArrayList<>(m.extraInfo().entrySet()),
                                (entry, i) -> obj.writeObject(pair -> {
                                    pair.writeField(MessageFields.ErrorInfoEntry.KEY, () -> pair.writeString(entry.getKey()));
                                    pair.writeField(MessageFields.ErrorInfoEntry.VALUE, () -> pair.writeString(entry.getValue()));
                                })));
                    }
                    if (m.mustInvalidate()) obj.writeField(MessageFields.ErrorResponse.MUST_INVALIDATE, () -> obj.writeBool(true));
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
                    optionalString(reader, MessageFields.ConnectionRequest.AUTH_STRING),
                    optionalString(reader, MessageFields.ConnectionRequest.CLIENT_DUCKDB_VERSION),
                    optionalString(reader, MessageFields.ConnectionRequest.CLIENT_PLATFORM),
                    Optional.of(reader.readOptionalField(MessageFields.ConnectionRequest.MIN_SUPPORTED_QUACK_VERSION, reader::readUlebLong, 0L)),
                    Optional.of(reader.readOptionalField(MessageFields.ConnectionRequest.MAX_SUPPORTED_QUACK_VERSION, reader::readUlebLong, 0L)),
                    optionalString(reader, MessageFields.ConnectionRequest.CLIENT_ID),
                    Optional.ofNullable(reader.readOptionalField(MessageFields.ConnectionRequest.HEARTBEAT_TIMEOUT_SECONDS, reader::readUlebLong, null))));
            case CONNECTION_RESPONSE -> reader.readObject(() -> new QuackMessage.ConnectionResponse(
                    header,
                    optionalString(reader, MessageFields.ConnectionResponse.SERVER_DUCKDB_VERSION),
                    optionalString(reader, MessageFields.ConnectionResponse.SERVER_PLATFORM),
                    Optional.ofNullable(reader.readOptionalField(MessageFields.ConnectionResponse.QUACK_VERSION, reader::readUlebLong, null)),
                    Optional.ofNullable(reader.readOptionalField(MessageFields.ConnectionResponse.HEARTBEAT_TIMEOUT_SECONDS, reader::readUlebLong, null))));
            case PREPARE_REQUEST -> reader.readObject(() -> new QuackMessage.PrepareRequest(
                    header,
                    reader.readOptionalField(MessageFields.PrepareRequest.SQL, reader::readString, ""),
                    protocolVersion == 3 ? reader.readRequiredField(MessageFields.PrepareRequest.QUERY_UUID, reader::readHugeInt)
                            : new HugeIntParts(0, 0),
                    protocolVersion == 3 ? optionalIndex(reader, MessageFields.PrepareRequest.INLINE_ROWS) : Optional.empty()));
            case PREPARE_RESPONSE -> reader.readObject(() -> {
                List<LogicalType> resultTypes = reader.readOptionalField(MessageFields.PrepareResponse.RESULT_TYPES,
                        () -> reader.readList(i -> LogicalTypeCodec.decode(reader)), List.of());
                List<String> resultNames = reader.readOptionalField(MessageFields.PrepareResponse.RESULT_NAMES,
                        () -> reader.readList(i -> reader.readString()), List.of());
                boolean needsMoreFetch = reader.readOptionalField(MessageFields.PrepareResponse.NEEDS_MORE_FETCH, reader::readBool, false);
                List<DataChunk> results = reader.readOptionalField(MessageFields.PrepareResponse.RESULTS,
                        () -> readChunkPointerList(reader), List.of());
                HugeIntParts resultUuid = reader.readRequiredField(MessageFields.PrepareResponse.RESULT_UUID, reader::readHugeInt);
                return new QuackMessage.PrepareResponse(header, resultTypes, resultNames,
                        needsMoreFetch, results, resultUuid);
            });
            case FETCH_REQUEST -> reader.readObject(() -> new QuackMessage.FetchRequest(
                    header, reader.readRequiredField(MessageFields.FetchRequest.RESULT_UUID, reader::readHugeInt),
                    protocolVersion == 3 ? reader.readOptionalField(MessageFields.FetchRequest.BATCH_INDEX, reader::readUlebLong, 0L) : 0,
                    protocolVersion == 3 ? reader.readOptionalField(MessageFields.FetchRequest.ACK_INDEX, reader::readUlebLong, 0L) : 0));
            case FETCH_RESPONSE -> {
                if (protocolVersion == 3) {
                    V3FetchBody body = reader.readObject(() -> new V3FetchBody(
                            reader.readOptionalField(MessageFields.FetchResponseV3.CHUNK_COUNT, reader::readUlebInt, 0),
                            optionalIndex(reader, MessageFields.FetchResponseV3.TOTAL_BATCHES),
                            optionalIndex(reader, MessageFields.FetchResponseV3.BATCH_INDEX)));
                    // The raw blob follows the body's end-of-object marker.
                    if (body.chunkCount() > reader.remaining() / 6) {
                        throw new QuackProtocolException("FETCH chunk count exceeds response size");
                    }
                    List<DataChunk> chunks = new ArrayList<>();
                    for (int i = 0; i < body.chunkCount(); i++) chunks.add(VectorCodec.decodeDataChunk(reader));
                    yield new QuackMessage.FetchResponse(header, chunks, body.batchIndex(), body.totalBatches());
                }
                yield reader.readObject(() -> {
                    List<DataChunk> results = reader.readOptionalField(MessageFields.FetchResponse.RESULTS,
                            () -> readChunkPointerList(reader), List.of());
                    long batchIndex = reader.readRequiredField(MessageFields.FetchResponse.BATCH_INDEX, reader::readUlebLong);
                    Optional<Long> batchOpt = batchIndex == QuackConstants.OPTIONAL_INDEX_INVALID
                            ? Optional.empty() : Optional.of(batchIndex);
                    return new QuackMessage.FetchResponse(header, results, batchOpt);
                });
            }
            case APPEND_REQUEST -> reader.readObject(() -> {
                Optional<String> schemaName = optionalString(reader, MessageFields.AppendRequest.SCHEMA_NAME);
                String tableName = reader.readOptionalField(MessageFields.AppendRequest.TABLE_NAME, reader::readString, "");
                DataChunk chunk = reader.readOptionalField(MessageFields.AppendRequest.APPEND_CHUNK,
                        () -> reader.readNullable(() -> VectorCodec.decodeDataChunkWrapper(reader)),
                        null);
                if (chunk == null) {
                    throw new QuackProtocolException("APPEND_REQUEST is missing append_chunk");
                }
                return new QuackMessage.AppendRequest(header, schemaName, tableName, chunk);
            });
            case SEND_DATA_REQUEST -> {
                V3SendBody body = reader.readObject(() -> new V3SendBody(
                        reader.readOptionalField(MessageFields.SendDataRequest.STREAM_ID, reader::readString, ""),
                        reader.readOptionalField(MessageFields.SendDataRequest.CHUNK_COUNT, reader::readUlebInt, 0),
                        optionalIndex(reader, MessageFields.SendDataRequest.TOTAL_BATCHES),
                        optionalIndex(reader, MessageFields.SendDataRequest.BATCH_INDEX)));
                if (body.chunkCount() > reader.remaining() / 6) {
                    throw new QuackProtocolException("SEND_DATA chunk count exceeds request size");
                }
                List<DataChunk> chunks = new ArrayList<>();
                for (int i = 0; i < body.chunkCount(); i++) chunks.add(VectorCodec.decodeDataChunk(reader));
                yield new QuackMessage.SendDataRequest(header, body.streamId(), chunks,
                        body.batchIndex(), body.totalBatches());
            }
            case SEND_DATA_RESPONSE -> reader.readObject(() -> new QuackMessage.SendDataResponse(
                    header, optionalIndex(reader, MessageFields.SendDataResponse.ACCEPT_BUDGET)));
            case SUCCESS_RESPONSE -> reader.readObject(() -> new QuackMessage.SuccessResponse(header));
            case DISCONNECT_MESSAGE -> reader.readObject(() -> new QuackMessage.DisconnectMessage(header));
            case CANCEL_REQUEST -> reader.readObject(() -> new QuackMessage.CancelRequest(
                    header, reader.readRequiredField(MessageFields.CancelRequest.QUERY_UUID, reader::readHugeInt)));
            case HEARTBEAT_REQUEST -> reader.readObject(() -> new QuackMessage.HeartbeatRequest(header));
            case ERROR_RESPONSE -> reader.readObject(() -> {
                String message = reader.readOptionalField(MessageFields.ErrorResponse.MESSAGE, reader::readString, "");
                // v3 can report an error before version negotiation (notably
                // the missing heartbeat lease). Consume its typed diagnostics
                // even when this connection is still decoding as v1.
                String exceptionType = reader.readOptionalField(MessageFields.ErrorResponse.EXCEPTION_TYPE, reader::readString, "");
                Map<String, String> extraInfo = reader.readOptionalField(MessageFields.ErrorResponse.EXTRA_INFO, () -> {
                    reader.reserve(64);
                    Map<String, String> entries = new LinkedHashMap<>();
                    reader.readList(i -> reader.readObject(() -> {
                        // Account for the map, its immutable copy, and temporary entry storage.
                        reader.reserve(128);
                        String key = reader.readRequiredField(MessageFields.ErrorInfoEntry.KEY, reader::readString);
                        String value = reader.readRequiredField(MessageFields.ErrorInfoEntry.VALUE, reader::readString);
                        entries.put(key, value);
                        return 0;
                    }));
                    return entries;
                }, Map.of());
                boolean mustInvalidate = reader.readOptionalField(MessageFields.ErrorResponse.MUST_INVALIDATE, reader::readBool, false);
                return new QuackMessage.ErrorResponse(header, message, exceptionType, extraInfo, mustInvalidate);
            });
            case INVALID -> throw new QuackProtocolException(
                    "Cannot decode message type " + header.type());
        };
    }

    private static Optional<String> optionalString(BinaryReader reader, WireField field) {
        return Optional.ofNullable(reader.readOptionalField(field, reader::readString, null));
    }

    private static Optional<Long> optionalIndex(BinaryReader reader, WireField field) {
        long raw = reader.readOptionalField(field, reader::readUlebLong, QuackConstants.OPTIONAL_INDEX_INVALID);
        return raw == QuackConstants.OPTIONAL_INDEX_INVALID ? Optional.empty() : Optional.of(raw);
    }

    private static void writeOptionalString(BinaryWriter writer, WireField field, Optional<String> value) {
        value.filter(s -> !s.isEmpty()).ifPresent(v ->
                writer.writeField(field, () -> writer.writeString(v)));
    }

    private static void writeOptionalIndexDefaultZero(BinaryWriter writer, WireField field, Optional<Long> value) {
        value.filter(v -> v != 0L).ifPresent(v ->
                writer.writeField(field, () -> writer.writeUleb(v)));
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
}

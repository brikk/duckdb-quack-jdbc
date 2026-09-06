package com.gizmodata.quack.jdbc.message;

import com.gizmodata.quack.jdbc.codec.HugeIntParts;
import com.gizmodata.quack.jdbc.type.LogicalType;

import java.util.List;
import java.util.Optional;

public sealed interface QuackMessage {

    MessageHeader header();

    /** HTTP sideband metadata only; never serialized in the v1 binary message. */
    enum ResultKind { QUERY, CHANGED_ROWS, NOTHING }

    record ConnectionRequest(MessageHeader header,
                             Optional<String> authString,
                             Optional<String> clientDuckdbVersion,
                             Optional<String> clientPlatform,
                             Optional<Long> minSupportedQuackVersion,
                             Optional<Long> maxSupportedQuackVersion) implements QuackMessage {}

    record ConnectionResponse(MessageHeader header,
                              Optional<String> serverDuckdbVersion,
                              Optional<String> serverPlatform,
                              Optional<Long> quackVersion,
                              Optional<Long> resultMetadataVersion) implements QuackMessage {
        public ConnectionResponse(MessageHeader header, Optional<String> serverDuckdbVersion,
                                  Optional<String> serverPlatform, Optional<Long> quackVersion) {
            this(header, serverDuckdbVersion, serverPlatform, quackVersion, Optional.empty());
        }
    }

    record PrepareRequest(MessageHeader header, String sql) implements QuackMessage {}

    record PrepareResponse(MessageHeader header,
                           List<LogicalType> resultTypes,
                           List<String> resultNames,
                           boolean needsMoreFetch,
                           List<DataChunk> results,
                           HugeIntParts resultUuid,
                           Optional<ResultKind> resultKind) implements QuackMessage {
        public PrepareResponse(MessageHeader header, List<LogicalType> resultTypes,
                               List<String> resultNames, boolean needsMoreFetch,
                               List<DataChunk> results, HugeIntParts resultUuid) {
            this(header, resultTypes, resultNames, needsMoreFetch, results, resultUuid, Optional.empty());
        }
    }

    record FetchRequest(MessageHeader header, HugeIntParts resultUuid) implements QuackMessage {}

    record FetchResponse(MessageHeader header,
                         List<DataChunk> results,
                         Optional<Long> batchIndex) implements QuackMessage {}

    record AppendRequest(MessageHeader header,
                         Optional<String> schemaName,
                         String tableName,
                         DataChunk appendChunk) implements QuackMessage {}

    record SuccessResponse(MessageHeader header) implements QuackMessage {}

    record DisconnectMessage(MessageHeader header) implements QuackMessage {}

    record ErrorResponse(MessageHeader header, String message) implements QuackMessage {}
}

package com.gizmodata.quack.jdbc.message;

import com.gizmodata.quack.jdbc.codec.HugeIntParts;
import com.gizmodata.quack.jdbc.type.LogicalType;

import java.util.List;
import java.util.Optional;

public sealed interface QuackMessage {

    MessageHeader header();

    record ConnectionRequest(MessageHeader header,
                             Optional<String> authString,
                             Optional<String> clientDuckdbVersion,
                             Optional<String> clientPlatform,
                             Optional<Long> minSupportedQuackVersion,
                             Optional<Long> maxSupportedQuackVersion) implements QuackMessage {}

    record ConnectionResponse(MessageHeader header,
                              Optional<String> serverDuckdbVersion,
                              Optional<String> serverPlatform,
                              Optional<Long> quackVersion) implements QuackMessage {}

    record PrepareRequest(MessageHeader header, String sql) implements QuackMessage {}

    record PrepareResponse(MessageHeader header,
                           List<LogicalType> resultTypes,
                           List<String> resultNames,
                           boolean needsMoreFetch,
                           List<DataChunk> results,
                           HugeIntParts resultUuid) implements QuackMessage {}

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

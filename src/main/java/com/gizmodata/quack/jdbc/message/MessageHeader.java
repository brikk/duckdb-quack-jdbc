package com.gizmodata.quack.jdbc.message;

import java.util.Optional;

public record MessageHeader(MessageType type, Optional<String> connectionId, Optional<Long> clientQueryId) {

    public static MessageHeader of(MessageType type) {
        return new MessageHeader(type, Optional.empty(), Optional.empty());
    }

    public MessageHeader withConnectionId(String connectionId) {
        return new MessageHeader(type, Optional.ofNullable(connectionId), clientQueryId);
    }

    public MessageHeader withClientQueryId(long id) {
        return new MessageHeader(type, connectionId, Optional.of(id));
    }
}

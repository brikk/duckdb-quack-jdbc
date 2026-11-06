package com.gizmodata.quack.jdbc.message;

import com.gizmodata.quack.jdbc.codec.WireField;

/** Field ids of the Quack message header and each message body, one enum per wire object. */
public final class MessageFields {

    private MessageFields() {
    }

    public enum Header implements WireField {
        MESSAGE_TYPE(1), CONNECTION_ID(2), CLIENT_QUERY_ID(3);

        private final int id;
        Header(int id) { this.id = id; }
        @Override public int id() { return id; }
    }

    public enum ConnectionRequest implements WireField {
        AUTH_STRING(1), CLIENT_DUCKDB_VERSION(2), CLIENT_PLATFORM(3),
        MIN_SUPPORTED_QUACK_VERSION(4), MAX_SUPPORTED_QUACK_VERSION(5),
        CLIENT_ID(6), HEARTBEAT_TIMEOUT_SECONDS(7);

        private final int id;
        ConnectionRequest(int id) { this.id = id; }
        @Override public int id() { return id; }
    }

    public enum ConnectionResponse implements WireField {
        SERVER_DUCKDB_VERSION(1), SERVER_PLATFORM(2), QUACK_VERSION(3), HEARTBEAT_TIMEOUT_SECONDS(4);

        private final int id;
        ConnectionResponse(int id) { this.id = id; }
        @Override public int id() { return id; }
    }

    public enum PrepareRequest implements WireField {
        SQL(1), QUERY_UUID(2), INLINE_ROWS(3);

        private final int id;
        PrepareRequest(int id) { this.id = id; }
        @Override public int id() { return id; }
    }

    public enum PrepareResponse implements WireField {
        RESULT_TYPES(1), RESULT_NAMES(2), NEEDS_MORE_FETCH(3), RESULTS(4), RESULT_UUID(5);

        private final int id;
        PrepareResponse(int id) { this.id = id; }
        @Override public int id() { return id; }
    }

    public enum FetchRequest implements WireField {
        RESULT_UUID(1), BATCH_INDEX(2), ACK_INDEX(3);

        private final int id;
        FetchRequest(int id) { this.id = id; }
        @Override public int id() { return id; }
    }

    /** v1 body: chunk pointers inline. */
    public enum FetchResponse implements WireField {
        RESULTS(1), BATCH_INDEX(2);

        private final int id;
        FetchResponse(int id) { this.id = id; }
        @Override public int id() { return id; }
    }

    /** v3 body: a chunk count, with the raw chunks following the end-of-object marker. */
    public enum FetchResponseV3 implements WireField {
        CHUNK_COUNT(1), TOTAL_BATCHES(2), BATCH_INDEX(3);

        private final int id;
        FetchResponseV3(int id) { this.id = id; }
        @Override public int id() { return id; }
    }

    public enum AppendRequest implements WireField {
        SCHEMA_NAME(1), TABLE_NAME(2), APPEND_CHUNK(3);

        private final int id;
        AppendRequest(int id) { this.id = id; }
        @Override public int id() { return id; }
    }

    public enum SendDataRequest implements WireField {
        STREAM_ID(1), CHUNK_COUNT(2), TOTAL_BATCHES(3), BATCH_INDEX(4);

        private final int id;
        SendDataRequest(int id) { this.id = id; }
        @Override public int id() { return id; }
    }

    public enum SendDataResponse implements WireField {
        ACCEPT_BUDGET(1);

        private final int id;
        SendDataResponse(int id) { this.id = id; }
        @Override public int id() { return id; }
    }

    public enum CancelRequest implements WireField {
        QUERY_UUID(1);

        private final int id;
        CancelRequest(int id) { this.id = id; }
        @Override public int id() { return id; }
    }

    public enum ErrorResponse implements WireField {
        MESSAGE(1), EXCEPTION_TYPE(2), EXTRA_INFO(3), MUST_INVALIDATE(4);

        private final int id;
        ErrorResponse(int id) { this.id = id; }
        @Override public int id() { return id; }
    }

    /** One entry of {@link ErrorResponse#EXTRA_INFO}. */
    public enum ErrorInfoEntry implements WireField {
        KEY(0), VALUE(1);

        private final int id;
        ErrorInfoEntry(int id) { this.id = id; }
        @Override public int id() { return id; }
    }
}

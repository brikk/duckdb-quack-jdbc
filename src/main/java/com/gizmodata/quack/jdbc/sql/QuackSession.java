package com.gizmodata.quack.jdbc.sql;

import com.gizmodata.quack.jdbc.QuackException;
import com.gizmodata.quack.jdbc.QuackProtocolException;
import com.gizmodata.quack.jdbc.QuackServerException;
import com.gizmodata.quack.jdbc.codec.HugeIntParts;
import com.gizmodata.quack.jdbc.codec.QuackConstants;
import com.gizmodata.quack.jdbc.message.DataChunk;
import com.gizmodata.quack.jdbc.message.MessageHeader;
import com.gizmodata.quack.jdbc.message.MessageType;
import com.gizmodata.quack.jdbc.message.QuackMessage;
import com.gizmodata.quack.jdbc.transport.QuackHttpTransport;
import com.gizmodata.quack.jdbc.transport.QuackTransport;
import com.gizmodata.quack.jdbc.transport.QuackTransportFactory;
import com.gizmodata.quack.jdbc.transport.QuackUri;
import com.gizmodata.quack.jdbc.type.LogicalType;

import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Live Quack session: connection id, query-id sequence, and a Quack transport. */
public final class QuackSession implements AutoCloseable {

    private final QuackUri uri;
    private final QuackTransport transport;
    private final QuackConnection connection;
    private final AtomicLong queryIdSeq = new AtomicLong(1);
    private volatile String connectionId;
    private volatile Optional<String> serverDuckdbVersion = Optional.empty();
    private volatile long protocolVersion = QuackConstants.QUACK_VERSION;
    private ScheduledExecutorService heartbeat;
    private volatile RuntimeException heartbeatFailure;
    private volatile boolean closed;

    public QuackSession(QuackUri uri, QuackTransport transport) {
        this(uri, transport, null);
    }

    private QuackSession(QuackUri uri, QuackTransport transport, QuackConnection connection) {
        this.uri = Objects.requireNonNull(uri, "uri");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.connection = connection;
    }

    public QuackSession(QuackUri uri, QuackHttpTransport transport) {
        this(uri, (QuackTransport) transport);
    }

    public static QuackSession connect(QuackUri uri) {
        return connect(uri, QuackTransportFactory.http());
    }

    public static QuackSession connect(QuackUri uri, QuackTransport transport) {
        QuackSession session = new QuackSession(uri, transport);
        session.handshake();
        return session;
    }

    public static QuackSession connect(QuackUri uri, QuackTransportFactory transportFactory) {
        return connect(uri, transportFactory, null);
    }

    static QuackSession connect(QuackUri uri, QuackTransportFactory transportFactory,
                                QuackConnection connection) {
        Objects.requireNonNull(transportFactory, "transportFactory");
        QuackSession session = new QuackSession(uri, Objects.requireNonNull(
                transportFactory.create(uri), "transportFactory returned null"), connection);
        session.handshake();
        return session;
    }

    public QuackUri uri() {
        return uri;
    }

    public String connectionId() {
        return connectionId;
    }

    public Optional<String> serverDuckdbVersion() {
        return serverDuckdbVersion;
    }

    public long protocolVersion() {
        return protocolVersion;
    }

    public boolean isClosed() {
        return closed;
    }

    private void handshake() {
        // A v1 server cannot deserialize the v3-only heartbeat field. Probe with
        // the shared v1 envelope first; a v3 server explicitly requests a lease.
        QuackMessage.ConnectionResponse connResp;
        try {
            connResp = connectRequest(false);
        } catch (QuackServerException e) {
            if (!e.getMessage().contains("heartbeat_timeout out of range")) throw e;
            connResp = connectRequest(true);
        }
        long selected = connResp.quackVersion().orElse(QuackConstants.QUACK_VERSION);
        if (selected != QuackConstants.QUACK_VERSION && selected != QuackConstants.LATEST_QUACK_VERSION) {
            throw new QuackProtocolException("Unsupported Quack protocol version " + selected
                    + " (supported: 1 and 3)");
        }
        if (selected == 3 && connResp.heartbeatTimeoutSeconds().orElse(0L) <= 0) {
            throw new QuackProtocolException("v3 server did not return a heartbeat lease");
        }
        this.connectionId = connResp.header().connectionId().orElseThrow(
                () -> new QuackProtocolException("Server did not return a connection_id"));
        this.serverDuckdbVersion = connResp.serverDuckdbVersion();
        this.protocolVersion = selected;
        transport.setProtocolVersion(selected);
        if (selected == 3) startHeartbeat(connResp.heartbeatTimeoutSeconds().orElseThrow());
    }

    private QuackMessage.ConnectionResponse connectRequest(boolean v3) {
        MessageHeader header = MessageHeader.of(MessageType.CONNECTION_REQUEST)
                .withClientQueryId(nextQueryId());
        QuackMessage.ConnectionRequest request = new QuackMessage.ConnectionRequest(
                header,
                uri.token(),
                Optional.of(DriverVersion.CLIENT_VERSION),
                Optional.of(System.getProperty("os.name", "unknown")),
                Optional.of(QuackConstants.QUACK_VERSION),
                Optional.of(QuackConstants.LATEST_QUACK_VERSION),
                Optional.empty(), v3 ? Optional.of(60L) : Optional.empty());
        QuackMessage response = transport.send(request);
        if (!(response instanceof QuackMessage.ConnectionResponse connResp)) {
            throw new QuackProtocolException(
                    "Expected CONNECTION_RESPONSE, got " + response.getClass().getSimpleName());
        }
        return connResp;
    }

    private void startHeartbeat(long leaseSeconds) {
        long interval = Math.max(1, Math.min(20, leaseSeconds / 3));
        heartbeat = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "quack-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
        heartbeat.scheduleWithFixedDelay(() -> {
            if (!closed && heartbeatFailure == null) {
                try {
                    QuackMessage response = transport.send(new QuackMessage.HeartbeatRequest(
                            MessageHeader.of(MessageType.HEARTBEAT_REQUEST)
                                    .withConnectionId(connectionId).withClientQueryId(nextQueryId())));
                    if (!(response instanceof QuackMessage.SuccessResponse)) {
                        heartbeatFailure = new QuackProtocolException("Unexpected heartbeat response: "
                                + response.getClass().getSimpleName());
                    }
                } catch (QuackServerException e) {
                    heartbeatFailure = e;
                } catch (RuntimeException e) {
                    // A transient heartbeat failure does not prove the lease expired;
                    // the next request or heartbeat will report the server's verdict.
                }
            }
        }, interval, interval, TimeUnit.SECONDS);
    }

    /**
     * Open a streaming cursor over a prepared query. Only the initial
     * batch of chunks is fetched up front (whatever the server included
     * in {@code PREPARE_RESPONSE}); subsequent chunks are fetched on
     * demand as the caller calls {@link Cursor#nextChunk()}.
     */
    public Cursor cursor(String sql) {
        return cursor(sql, Optional.empty());
    }

    private Cursor cursor(String sql, Optional<Long> inlineRows) {
        if (heartbeatFailure != null) throw heartbeatFailure;
        if (closed) {
            throw new QuackProtocolException("Session is closed");
        }
        QuackMessage.PrepareRequest request = new QuackMessage.PrepareRequest(
                MessageHeader.of(MessageType.PREPARE_REQUEST)
                        .withConnectionId(connectionId)
                        .withClientQueryId(nextQueryId()),
                sql, protocolVersion == 3 ? new HugeIntParts(0, nextQueryId()) : new HugeIntParts(0, 0),
                inlineRows);
        QuackMessage response = transport.send(request);
        if (!(response instanceof QuackMessage.PrepareResponse prep)) {
            throw new QuackProtocolException(
                    "Expected PREPARE_RESPONSE, got " + response.getClass().getSimpleName());
        }
        if (protocolVersion == 3 && !prep.resultUuid().equals(request.queryUuid())) {
            throw new QuackProtocolException("PREPARE returned a different query UUID");
        }
        return new Cursor(this, prep);
    }

    /**
     * Append a {@link DataChunk} into {@code schema.tableName} via a Quack
     * {@code APPEND_REQUEST}. The chunk's column count and types must
     * match the destination table; rows are appended atomically as a
     * single server-side batch.
     *
     * <p>Sessions obtained from {@link QuackConnection#session()} honor that
     * connection's auto-commit mode, starting its transaction if needed.
     * Independently connected sessions leave transaction management to the caller.
     *
     * <p>This is the bulk-load fast-path — it sends column-oriented
     * binary data directly, bypassing per-row INSERT parsing. For typical
     * workloads it's an order of magnitude faster than
     * {@code PreparedStatement.executeBatch()}.
     */
    public void appendChunk(String schema, String tableName, DataChunk chunk) {
        if (heartbeatFailure != null) throw heartbeatFailure;
        if (closed) {
            throw new QuackProtocolException("Session is closed");
        }
        if (tableName == null || tableName.isEmpty()) {
            throw new QuackProtocolException("appendChunk: tableName is required");
        }
        if (chunk == null) {
            throw new QuackProtocolException("appendChunk: chunk is required");
        }
        if (connection != null) {
            try {
                connection.beginTransactionIfNeeded();
            } catch (SQLException e) {
                throw new QuackException("Could not start transaction for APPEND", e);
            }
        }
        if (protocolVersion == 3) {
            appendV3(schema, tableName, chunk);
            return;
        }
        QuackMessage.AppendRequest request = new QuackMessage.AppendRequest(
                MessageHeader.of(MessageType.APPEND_REQUEST)
                        .withConnectionId(connectionId)
                        .withClientQueryId(nextQueryId()),
                Optional.ofNullable(schema),
                tableName,
                chunk);
        QuackMessage response = transport.send(request);
        if (!(response instanceof QuackMessage.SuccessResponse)) {
            throw new QuackProtocolException(
                    "Expected SUCCESS_RESPONSE for APPEND, got "
                            + response.getClass().getSimpleName());
        }
    }

    private void appendV3(String schema, String tableName, DataChunk chunk) {
        if (chunk.rowCount() == 0) return;
        String target = (schema == null || schema.isEmpty() ? "" : quoteIdentifier(schema) + ".")
                + quoteIdentifier(tableName);
        // Derive the prototype from the destination, preserving column order and
        // names (the DataChunk itself only contains types). Validate before sending.
        List<String> names;
        try (Cursor columns = cursor("SELECT * FROM " + target + " LIMIT 0")) {
            names = columns.columnNames();
            if (!columns.columnTypes().equals(chunk.types())) {
                throw new QuackProtocolException("APPEND chunk types do not match " + target);
            }
        }
        if (names.isEmpty()) throw new QuackProtocolException("APPEND requires at least one column");
        String streamId = UUID.randomUUID().toString();
        StringBuilder prototype = new StringBuilder("NULL::STRUCT(");
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) prototype.append(", ");
            prototype.append(quoteIdentifier(names.get(i))).append(' ')
                    .append(JdbcTypeMap.typeName(chunk.types().get(i), true));
        }
        prototype.append(')');
        String sql = "INSERT INTO " + target + " SELECT * FROM scan_data_from_quack_client("
                + SqlLiteral.render(streamId) + ", " + prototype + ")";
        // Zero inline rows lets PREPARE return once the stream is bound,
        // rather than blocking until SEND_DATA has supplied the first batch.
        try (Cursor result = cursor(sql, Optional.of(0L))) {
            QuackMessage response = transport.send(new QuackMessage.SendDataRequest(
                    MessageHeader.of(MessageType.SEND_DATA_REQUEST)
                            .withConnectionId(connectionId).withClientQueryId(nextQueryId()),
                    streamId, List.of(chunk), Optional.of(1L), Optional.empty()));
            if (!(response instanceof QuackMessage.SendDataResponse)) {
                throw new QuackProtocolException("Expected SEND_DATA_RESPONSE, got "
                        + response.getClass().getSimpleName());
            }
            response = transport.send(new QuackMessage.SendDataRequest(
                    MessageHeader.of(MessageType.SEND_DATA_REQUEST)
                            .withConnectionId(connectionId).withClientQueryId(nextQueryId()),
                    streamId, List.of(), Optional.empty(), Optional.of(1L)));
            if (!(response instanceof QuackMessage.SendDataResponse)) {
                throw new QuackProtocolException("Expected terminal SEND_DATA_RESPONSE, got "
                        + response.getClass().getSimpleName());
            }
            result.drainAll(); // surface any late INSERT failure
        }
    }

    private static String quoteIdentifier(String name) {
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }

    @Override
    public synchronized void close() {
        if (heartbeat != null) heartbeat.shutdownNow();
        if (closed || connectionId == null) {
            closed = true;
            return;
        }
        try {
            QuackMessage.DisconnectMessage disconnect = new QuackMessage.DisconnectMessage(
                    MessageHeader.of(MessageType.DISCONNECT_MESSAGE)
                            .withConnectionId(connectionId)
                            .withClientQueryId(nextQueryId()));
            transport.send(disconnect);
        } catch (RuntimeException ignored) {
            // Best-effort disconnect.
        } finally {
            closed = true;
        }
    }

    private long nextQueryId() {
        return queryIdSeq.getAndIncrement();
    }

    private List<DataChunk> fetchMoreChunks(HugeIntParts resultUuid, FetchState state) {
        if (heartbeatFailure != null) throw heartbeatFailure;
        long batchIndex = protocolVersion == 3 ? state.nextBatchIndex : 0;
        QuackMessage.FetchRequest fetch = new QuackMessage.FetchRequest(
                MessageHeader.of(MessageType.FETCH_REQUEST)
                        .withConnectionId(connectionId)
                        .withClientQueryId(nextQueryId()),
                resultUuid, batchIndex, batchIndex > 1 ? batchIndex - 1 : 0);
        QuackMessage fr = transport.send(fetch);
        if (!(fr instanceof QuackMessage.FetchResponse fetchResp)) {
            throw new QuackProtocolException(
                    "Expected FETCH_RESPONSE, got " + fr.getClass().getSimpleName());
        }
        if (protocolVersion == 3) {
            if (fetchResp.batchIndex().isPresent()) {
                if (fetchResp.batchIndex().get() != batchIndex || fetchResp.totalBatches().isPresent()
                        || fetchResp.results().isEmpty()) {
                    throw new QuackProtocolException("Invalid v3 FETCH batch " + batchIndex);
                }
                state.nextBatchIndex++;
            } else {
                if (!fetchResp.results().isEmpty() || fetchResp.totalBatches().isEmpty()
                        || fetchResp.totalBatches().get() != batchIndex - 1) {
                    throw new QuackProtocolException("Invalid v3 FETCH terminal batch count");
                }
                state.hasMore = false;
            }
        } else {
            state.hasMore = fetchResp.batchIndex().isPresent();
        }
        return fetchResp.results();
    }

    /**
     * Streaming cursor over the chunks of a Quack-prepared query.
     *
     * <p>Holds at most one server batch in memory at a time
     * (whatever {@code quack_fetch_batch_chunks} is set to on the
     * server, default 12). {@link #nextChunk()} pulls from the
     * buffered batch and issues a fresh {@code FETCH_REQUEST} when
     * the buffer is empty and the server signalled more chunks were
     * available.
     */
    public static final class Cursor implements AutoCloseable {

        private final QuackSession session;
        private final List<String> columnNames;
        private final List<LogicalType> columnTypes;
        private final HugeIntParts resultUuid;
        private final Deque<DataChunk> buffered;
        private final FetchState fetchState;
        private boolean closed;
        private int materializedRowCount;

        Cursor(QuackSession session, QuackMessage.PrepareResponse prep) {
            this.session = session;
            this.columnNames = prep.resultNames();
            this.columnTypes = prep.resultTypes();
            this.resultUuid = prep.resultUuid();
            this.buffered = new ArrayDeque<>(prep.results());
            this.fetchState = new FetchState(prep.needsMoreFetch());
            for (DataChunk c : prep.results()) materializedRowCount += c.rowCount();
        }

        public List<String> columnNames() {
            return columnNames;
        }

        public List<LogicalType> columnTypes() {
            return columnTypes;
        }

        /** Peek at the first buffered chunk without advancing the cursor. */
        public DataChunk peekFirstChunk() {
            return buffered.peek();
        }

        /**
         * Return the next chunk, fetching from the server if the local
         * buffer is empty and more chunks are available. Returns
         * {@code null} when the result set is exhausted.
         */
        public DataChunk nextChunk() {
            if (closed) return null;
            if (buffered.isEmpty() && fetchState.hasMore) {
                List<DataChunk> next = session.fetchMoreChunks(resultUuid, fetchState);
                buffered.addAll(next);
                for (DataChunk c : next) materializedRowCount += c.rowCount();
            }
            return buffered.poll();
        }

        /** Drain every remaining chunk and return them in order. */
        public List<DataChunk> drainAll() {
            List<DataChunk> all = new ArrayList<>(buffered);
            buffered.clear();
            while (fetchState.hasMore) {
                List<DataChunk> next = session.fetchMoreChunks(resultUuid, fetchState);
                all.addAll(next);
                for (DataChunk c : next) materializedRowCount += c.rowCount();
            }
            return all;
        }

        /**
         * Approximate count of rows already pulled across the wire (not the
         * total result-set size — only what the cursor has seen). Useful for
         * diagnostics and tests.
         */
        public int materializedRowCount() {
            return materializedRowCount;
        }

        @Override
        public void close() {
            closed = true;
            buffered.clear();
            // Quack has no explicit "release result" message yet; the server
            // releases state on DISCONNECT or after all chunks have been
            // fetched. Drain in the background if needed to free server-side
            // state, but for now leave any un-fetched chunks to be reaped on
            // disconnect — closing a JDBC ResultSet shouldn't block.
        }
    }

    private static final class FetchState {
        boolean hasMore;
        long nextBatchIndex = 1;

        FetchState(boolean hasMore) {
            this.hasMore = hasMore;
        }
    }
}

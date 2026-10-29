package com.gizmodata.quack.jdbc.sql;

import com.gizmodata.quack.jdbc.QuackException;
import com.gizmodata.quack.jdbc.QuackCancelledException;
import com.gizmodata.quack.jdbc.QuackProtocolException;
import com.gizmodata.quack.jdbc.QuackServerException;
import com.gizmodata.quack.jdbc.codec.HugeIntParts;
import com.gizmodata.quack.jdbc.codec.QuackConstants;
import com.gizmodata.quack.jdbc.message.DataChunk;
import com.gizmodata.quack.jdbc.message.DecodedVector;
import com.gizmodata.quack.jdbc.message.MessageHeader;
import com.gizmodata.quack.jdbc.message.MessageType;
import com.gizmodata.quack.jdbc.message.QuackMessage;
import com.gizmodata.quack.jdbc.transport.QuackHttpTransport;
import com.gizmodata.quack.jdbc.transport.QuackTransport;
import com.gizmodata.quack.jdbc.transport.QuackTransportFactory;
import com.gizmodata.quack.jdbc.transport.QuackUri;
import com.gizmodata.quack.jdbc.type.LogicalType;
import com.gizmodata.quack.jdbc.type.ExtraTypeInfo;
import com.gizmodata.quack.jdbc.type.LogicalTypeId;

import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeoutException;

/** Live Quack session: connection id, query-id sequence, and a Quack transport. */
public final class QuackSession implements AutoCloseable {

    private final QuackUri uri;
    private final QuackTransport transport;
    private final QuackConnection connection;
    private final AtomicLong queryIdSeq = new AtomicLong(1);
    // Never held by PREPARE/FETCH network I/O. A CANCEL holds it until its reply so
    // a newer PREPARE cannot cross the server's separate UUID-check and abort steps.
    private final Object queryGate = new Object();
    private Query currentQuery;
    private volatile String connectionId;
    private volatile Optional<String> serverDuckdbVersion = Optional.empty();
    private volatile long protocolVersion = QuackConstants.QUACK_VERSION;
    private volatile ScheduledExecutorService heartbeat;
    private volatile RuntimeException heartbeatFailure;
    private volatile QuackServerException invalidationFailure;
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

    /** The server explicitly reported that its database can no longer serve this session. */
    public boolean isInvalidated() {
        return invalidationFailure != null;
    }

    void checkUsable() {
        if (closed) throw new QuackProtocolException("Session is closed");
        if (invalidationFailure != null) {
            throw new QuackException("The Quack server invalidated its database; close this connection "
                    + "and reconnect after the server is restarted", invalidationFailure);
        }
        if (heartbeatFailure != null) throw heartbeatFailure;
    }

    private QuackMessage send(QuackMessage request) {
        checkUsable();
        try {
            QuackMessage response = transport.send(request);
            // Custom transports may return errors rather than throw them like the HTTP transport.
            if (response instanceof QuackMessage.ErrorResponse error) {
                throw new QuackServerException(error.message(), error.exceptionType(),
                        error.extraInfo(), error.mustInvalidate());
            }
            return response;
        } catch (QuackServerException e) {
            if (e.mustInvalidate()) {
                invalidationFailure = e;
                ScheduledExecutorService currentHeartbeat = heartbeat;
                if (currentHeartbeat != null) currentHeartbeat.shutdown();
            }
            throw e;
        }
    }

    private void handshake() {
        // A v1 server cannot deserialize the v3-only heartbeat field. Probe with
        // the shared v1 envelope first; a v3 server explicitly requests a lease.
        QuackMessage.ConnectionResponse connResp;
        try {
            connResp = connectRequest(false);
        } catch (QuackServerException e) {
            if (e.mustInvalidate() || !e.getMessage().contains("heartbeat_timeout out of range")) throw e;
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
        QuackMessage response = send(request);
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
        heartbeat.scheduleWithFixedDelay(this::sendHeartbeat, interval, interval, TimeUnit.SECONDS);
    }

    void sendHeartbeat() {
        if (closed || heartbeatFailure != null || isInvalidated()) return;
        try {
            QuackMessage response = send(new QuackMessage.HeartbeatRequest(
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
        return cursor(sql, inlineRows, newQuery());
    }

    Query newQuery() {
        return new Query(protocolVersion == 3 ? new HugeIntParts(0, nextQueryId()) : new HugeIntParts(0, 0));
    }

    Cursor cursor(String sql, Query query) {
        return cursor(sql, Optional.empty(), query);
    }

    private Cursor cursor(String sql, Optional<Long> inlineRows, Query query) {
        checkUsable();
        synchronized (queryGate) {
            checkUsable();
            query.checkCancelled();
            currentQuery = query;
        }
        QuackMessage.PrepareRequest request = new QuackMessage.PrepareRequest(
                MessageHeader.of(MessageType.PREPARE_REQUEST)
                        .withConnectionId(connectionId)
                        .withClientQueryId(nextQueryId()),
                sql, query.uuid,
                // Keep the first batch inline: JDBC needs the initial row to
                // distinguish DML counts from result sets and surface errors.
                // v3 drains a whole batch before checking the row hint. A hint
                // of 1 therefore requests one batch, not a strict one-row cap.
                protocolVersion == 3 && inlineRows.isEmpty() ? Optional.of(1L) : inlineRows);
        try {
            query.checkCancelled();
            QuackMessage response = send(request);
            query.checkCancelled();
            if (!(response instanceof QuackMessage.PrepareResponse prep)) {
                throw new QuackProtocolException(
                        "Expected PREPARE_RESPONSE, got " + response.getClass().getSimpleName());
            }
            if (protocolVersion == 3 && !prep.resultUuid().equals(request.queryUuid())) {
                throw new QuackProtocolException("PREPARE returned a different query UUID");
            }
            return new Cursor(this, prep, query);
        } catch (RuntimeException e) {
            query.done = true;
            throw query.failure(e);
        }
    }

    void cancel(Query query) {
        synchronized (queryGate) {
            if (query.done || query.cancelled) return;
            query.cancelled = true;
            // An older statement may still own buffered rows, but it must never
            // send a wildcard or cancel a newer statement on this connection.
            if (currentQuery != query) return;
            try {
                QuackMessage response = send(new QuackMessage.CancelRequest(
                        MessageHeader.of(MessageType.CANCEL_REQUEST)
                                .withConnectionId(connectionId).withClientQueryId(nextQueryId()), query.uuid));
                if (!(response instanceof QuackMessage.SuccessResponse)) {
                    throw new QuackProtocolException("Expected SUCCESS_RESPONSE for CANCEL");
                }
            } catch (RuntimeException e) {
                // In particular, the pinned server can reject a cancel arriving
                // before PREPARE installs its UUID. Do not guess or replay SQL:
                // close this session rather than advertise a successful cancel.
                if (connection == null) close();
                else connection.close();
                throw new QuackException("Cancellation could not be confirmed; the session was closed", e);
            }
        }
    }

    static final class Query {
        final HugeIntParts uuid;
        volatile boolean cancelled;
        volatile boolean done;

        Query(HugeIntParts uuid) { this.uuid = uuid; }

        void checkCancelled() {
            if (cancelled) throw new QuackCancelledException();
        }

        RuntimeException failure(RuntimeException error) {
            if (cancelled && !(error instanceof QuackCancelledException)
                    && !(error instanceof QuackServerException server && server.mustInvalidate())) {
                return new QuackCancelledException(error);
            }
            return error;
        }
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
        checkUsable();
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
        QuackMessage response = send(request);
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
        List<LogicalType> targetTypes;
        try (Cursor columns = cursor("SELECT * FROM " + target + " LIMIT 0")) {
            names = columns.columnNames();
            targetTypes = columns.columnTypes();
            if (targetTypes.size() != chunk.types().size()) {
                throw new QuackProtocolException("APPEND chunk types do not match " + target);
            }
            for (int i = 0; i < chunk.types().size(); i++) {
                if (!appendTypeCompatible(targetTypes.get(i), chunk.types().get(i))) {
                    throw new QuackProtocolException("APPEND chunk types do not match " + target);
                }
            }
        }
        if (names.isEmpty()) throw new QuackProtocolException("APPEND requires at least one column");
        List<DecodedVector> converted = new ArrayList<>(chunk.columns().size());
        for (int i = 0; i < chunk.columns().size(); i++) {
            DecodedVector column = chunk.columns().get(i);
            if (targetTypes.get(i).equals(chunk.types().get(i))) {
                converted.add(column);
            } else {
                Object[] values = new Object[chunk.rowCount()];
                for (int row = 0; row < values.length; row++) {
                    values[row] = adaptAppendValue(column.getObject(row), targetTypes.get(i), chunk.types().get(i));
                }
                converted.add(new DecodedVector.ObjectVec(targetTypes.get(i), values));
            }
        }
        DataChunk sendChunk = new DataChunk(chunk.rowCount(), targetTypes, converted);
        String streamId = UUID.randomUUID().toString();
        StringBuilder prototype = new StringBuilder("NULL::STRUCT(");
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) prototype.append(", ");
            prototype.append(quoteIdentifier(names.get(i))).append(' ')
                    .append(JdbcTypeMap.typeName(targetTypes.get(i), true));
        }
        prototype.append(')');
        String sql = "INSERT INTO " + target + " SELECT * FROM scan_data_from_quack_client("
                + SqlLiteral.render(streamId) + ", " + prototype + ")";
        // Zero inline rows lets PREPARE return once the stream is bound,
        // rather than blocking until SEND_DATA has supplied the first batch.
        try (Cursor result = cursor(sql, Optional.of(0L))) {
            QuackMessage response = send(new QuackMessage.SendDataRequest(
                    MessageHeader.of(MessageType.SEND_DATA_REQUEST)
                            .withConnectionId(connectionId).withClientQueryId(nextQueryId()),
                    streamId, List.of(sendChunk), Optional.of(1L), Optional.empty()));
            if (!(response instanceof QuackMessage.SendDataResponse)) {
                throw new QuackProtocolException("Expected SEND_DATA_RESPONSE, got "
                        + response.getClass().getSimpleName());
            }
            response = send(new QuackMessage.SendDataRequest(
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

    private static boolean appendTypeCompatible(LogicalType target, LogicalType source) {
        if (target.equals(source)) return true;
        if ((target.id() == LogicalTypeId.STRUCT || target.id() == LogicalTypeId.TUPLE)
                && (source.id() == LogicalTypeId.STRUCT || source.id() == LogicalTypeId.TUPLE)
                && target.typeInfo().orElse(null) instanceof ExtraTypeInfo.StructInfo to
                && source.typeInfo().orElse(null) instanceof ExtraTypeInfo.StructInfo from) {
            if (to.childTypes().size() != from.childTypes().size()
                    || from.childTypes().stream().anyMatch(child -> !child.name().isEmpty())) return false;
            for (int i = 0; i < to.childTypes().size(); i++) {
                if (!appendTypeCompatible(to.childTypes().get(i).type(), from.childTypes().get(i).type())) return false;
            }
            return true;
        }
        if (target.id() == LogicalTypeId.LIST && source.id() == LogicalTypeId.LIST
                && target.typeInfo().orElse(null) instanceof ExtraTypeInfo.ListInfo to
                && source.typeInfo().orElse(null) instanceof ExtraTypeInfo.ListInfo from) {
            return appendTypeCompatible(to.childType(), from.childType());
        }
        if (target.id() == LogicalTypeId.ARRAY && source.id() == LogicalTypeId.ARRAY
                && target.typeInfo().orElse(null) instanceof ExtraTypeInfo.ArrayInfo to
                && source.typeInfo().orElse(null) instanceof ExtraTypeInfo.ArrayInfo from) {
            return to.size() == from.size() && appendTypeCompatible(to.childType(), from.childType());
        }
        return false;
    }

    private static Object adaptAppendValue(Object value, LogicalType target, LogicalType source) {
        if (value == null || target.equals(source)) return value;
        if ((target.id() == LogicalTypeId.STRUCT || target.id() == LogicalTypeId.TUPLE)
                && target.typeInfo().orElse(null) instanceof ExtraTypeInfo.StructInfo to
                && source.typeInfo().orElse(null) instanceof ExtraTypeInfo.StructInfo from
                && value instanceof List<?> fields) {
            Map<String, Object> named = new LinkedHashMap<>();
            for (int i = 0; i < to.childTypes().size(); i++) {
                named.put(to.childTypes().get(i).name(), adaptAppendValue(fields.get(i),
                        to.childTypes().get(i).type(), from.childTypes().get(i).type()));
            }
            return named;
        }
        if (target.id() == LogicalTypeId.LIST && source.id() == LogicalTypeId.LIST
                && target.typeInfo().orElse(null) instanceof ExtraTypeInfo.ListInfo to
                && source.typeInfo().orElse(null) instanceof ExtraTypeInfo.ListInfo from
                && value instanceof List<?> elements) {
            List<Object> converted = new ArrayList<>(elements.size());
            for (Object element : elements) {
                converted.add(adaptAppendValue(element, to.childType(), from.childType()));
            }
            return converted;
        }
        if (target.id() == LogicalTypeId.ARRAY && source.id() == LogicalTypeId.ARRAY
                && target.typeInfo().orElse(null) instanceof ExtraTypeInfo.ArrayInfo to
                && source.typeInfo().orElse(null) instanceof ExtraTypeInfo.ArrayInfo from
                && value instanceof List<?> elements) {
            List<Object> converted = new ArrayList<>(elements.size());
            for (Object element : elements) {
                converted.add(adaptAppendValue(element, to.childType(), from.childType()));
            }
            return converted;
        }
        throw new QuackProtocolException("APPEND value does not match destination type");
    }

    private static String quoteIdentifier(String name) {
        return "\"" + name.replace("\"", "\"\"") + "\"";
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        if (heartbeat != null) heartbeat.shutdownNow();
        if (connectionId == null || isInvalidated()) return;
        FutureTask<QuackMessage> task = new FutureTask<>(() -> transport.send(new QuackMessage.DisconnectMessage(
                MessageHeader.of(MessageType.DISCONNECT_MESSAGE)
                        .withConnectionId(connectionId).withClientQueryId(nextQueryId()))));
        Thread worker = new Thread(task, "quack-disconnect");
        worker.setDaemon(true);
        try {
            worker.start();
            task.get(QuackHttpTransport.DEFAULT_DISCONNECT_TIMEOUT.toNanos(), TimeUnit.NANOSECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (ExecutionException | TimeoutException | RuntimeException ignored) {
            // Best-effort disconnect.
        } finally {
            task.cancel(true);
        }
    }

    private long nextQueryId() {
        return queryIdSeq.getAndIncrement();
    }

    private List<DataChunk> fetchMoreChunks(HugeIntParts resultUuid, FetchState state) {
        long batchIndex = protocolVersion == 3 ? state.nextBatchIndex : 0;
        QuackMessage.FetchRequest fetch = new QuackMessage.FetchRequest(
                MessageHeader.of(MessageType.FETCH_REQUEST)
                        .withConnectionId(connectionId)
                        .withClientQueryId(nextQueryId()),
                resultUuid, batchIndex, batchIndex > 1 ? batchIndex - 1 : 0);
        QuackMessage fr = send(fetch);
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
     * <p>Holds at most one FETCH batch in memory at a time. v1 batches
     * use {@code quack_fetch_batch_chunks}; v3 uses byte-sized batches.
     * Compressed batches can expand into many rows. {@link #nextChunk()} pulls from the
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
        private final Query query;
        private boolean closed;
        private int materializedRowCount;

        Cursor(QuackSession session, QuackMessage.PrepareResponse prep) {
            this(session, prep, null);
        }

        private Cursor(QuackSession session, QuackMessage.PrepareResponse prep, Query query) {
            this.session = session;
            this.query = query;
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
            checkCancelled();
            return buffered.peek();
        }

        void checkCancelled() {
            if (query != null) query.checkCancelled();
        }

        /**
         * Return the next chunk, fetching from the server if the local
         * buffer is empty and more chunks are available. Returns
         * {@code null} when the result set is exhausted.
         */
        public DataChunk nextChunk() {
            checkCancelled();
            if (closed) return null;
            if (buffered.isEmpty() && fetchState.hasMore) {
                List<DataChunk> next = fetch();
                buffered.addAll(next);
                for (DataChunk c : next) materializedRowCount += c.rowCount();
            }
            checkCancelled();
            if (buffered.isEmpty() && !fetchState.hasMore && query != null) query.done = true;
            return buffered.poll();
        }

        private List<DataChunk> fetch() {
            try {
                checkCancelled();
                List<DataChunk> next = session.fetchMoreChunks(resultUuid, fetchState);
                checkCancelled();
                return next;
            } catch (RuntimeException e) {
                if (query != null) throw query.failure(e);
                throw e;
            }
        }

        /** Drain every remaining chunk and return them in order. */
        public List<DataChunk> drainAll() {
            checkCancelled();
            List<DataChunk> all = new ArrayList<>(buffered);
            buffered.clear();
            while (fetchState.hasMore) {
                List<DataChunk> next = fetch();
                all.addAll(next);
                for (DataChunk c : next) materializedRowCount += c.rowCount();
            }
            checkCancelled();
            if (query != null) query.done = true;
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
            if (query != null) query.done = true;
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

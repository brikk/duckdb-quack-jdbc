package com.gizmodata.quack.jdbc.sql;

import com.gizmodata.quack.jdbc.QuackProtocolException;
import com.gizmodata.quack.jdbc.codec.HugeIntParts;
import com.gizmodata.quack.jdbc.codec.QuackConstants;
import com.gizmodata.quack.jdbc.message.DataChunk;
import com.gizmodata.quack.jdbc.message.MessageHeader;
import com.gizmodata.quack.jdbc.message.MessageType;
import com.gizmodata.quack.jdbc.message.QuackMessage;
import com.gizmodata.quack.jdbc.transport.QuackHttpTransport;
import com.gizmodata.quack.jdbc.transport.QuackUri;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/** Live Quack session: connection id, query-id sequence, and an HTTP transport. */
public final class QuackSession implements AutoCloseable {

    private final QuackUri uri;
    private final QuackHttpTransport transport;
    private final AtomicLong queryIdSeq = new AtomicLong(1);
    private volatile String connectionId;
    private volatile boolean closed;

    public QuackSession(QuackUri uri, QuackHttpTransport transport) {
        this.uri = uri;
        this.transport = transport;
    }

    public static QuackSession connect(QuackUri uri) {
        QuackHttpTransport transport = new QuackHttpTransport(uri.httpUri());
        QuackSession session = new QuackSession(uri, transport);
        session.handshake();
        return session;
    }

    public QuackUri uri() {
        return uri;
    }

    public String connectionId() {
        return connectionId;
    }

    public boolean isClosed() {
        return closed;
    }

    private void handshake() {
        MessageHeader header = MessageHeader.of(MessageType.CONNECTION_REQUEST)
                .withClientQueryId(nextQueryId());
        QuackMessage.ConnectionRequest request = new QuackMessage.ConnectionRequest(
                header,
                uri.token(),
                Optional.of("quack-jdbc/0.1.0"),
                Optional.of(System.getProperty("os.name", "unknown")),
                Optional.of(QuackConstants.QUACK_VERSION),
                Optional.of(QuackConstants.QUACK_VERSION));
        QuackMessage response = transport.send(request);
        if (!(response instanceof QuackMessage.ConnectionResponse connResp)) {
            throw new QuackProtocolException(
                    "Expected CONNECTION_RESPONSE, got " + response.getClass().getSimpleName());
        }
        this.connectionId = connResp.header().connectionId().orElseThrow(
                () -> new QuackProtocolException("Server did not return a connection_id"));
    }

    public PreparedResult prepare(String sql) {
        if (closed) {
            throw new QuackProtocolException("Session is closed");
        }
        long qid = nextQueryId();
        QuackMessage.PrepareRequest request = new QuackMessage.PrepareRequest(
                MessageHeader.of(MessageType.PREPARE_REQUEST)
                        .withConnectionId(connectionId)
                        .withClientQueryId(qid),
                sql);
        QuackMessage response = transport.send(request);
        if (!(response instanceof QuackMessage.PrepareResponse prep)) {
            throw new QuackProtocolException(
                    "Expected PREPARE_RESPONSE, got " + response.getClass().getSimpleName());
        }

        List<DataChunk> all = new ArrayList<>(prep.results());
        HugeIntParts resultUuid = prep.resultUuid();
        boolean more = prep.needsMoreFetch();
        long queryId = qid;
        while (more) {
            queryId = nextQueryId();
            QuackMessage.FetchRequest fetch = new QuackMessage.FetchRequest(
                    MessageHeader.of(MessageType.FETCH_REQUEST)
                            .withConnectionId(connectionId)
                            .withClientQueryId(queryId),
                    resultUuid);
            QuackMessage fr = transport.send(fetch);
            if (!(fr instanceof QuackMessage.FetchResponse fetchResp)) {
                throw new QuackProtocolException(
                        "Expected FETCH_RESPONSE, got " + fr.getClass().getSimpleName());
            }
            all.addAll(fetchResp.results());
            more = fetchResp.batchIndex().isPresent();
        }
        return new PreparedResult(prep.resultNames(), prep.resultTypes(), all);
    }

    @Override
    public synchronized void close() {
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

    public record PreparedResult(List<String> columnNames,
                                 List<com.gizmodata.quack.jdbc.type.LogicalType> columnTypes,
                                 List<DataChunk> chunks) {
        public int totalRowCount() {
            int total = 0;
            for (DataChunk c : chunks) total += c.rowCount();
            return total;
        }
    }
}

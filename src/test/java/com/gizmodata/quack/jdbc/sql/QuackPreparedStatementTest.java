package com.gizmodata.quack.jdbc.sql;

import com.gizmodata.quack.jdbc.codec.HugeIntParts;
import com.gizmodata.quack.jdbc.message.DataChunk;
import com.gizmodata.quack.jdbc.message.DecodedVector;
import com.gizmodata.quack.jdbc.message.MessageHeader;
import com.gizmodata.quack.jdbc.message.MessageType;
import com.gizmodata.quack.jdbc.message.QuackMessage;
import com.gizmodata.quack.jdbc.transport.QuackUri;
import com.gizmodata.quack.jdbc.type.LogicalType;
import com.gizmodata.quack.jdbc.type.LogicalTypeId;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.Reader;
import java.lang.reflect.Proxy;
import java.sql.Array;
import java.sql.ParameterMetaData;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuackPreparedStatementTest {
    @Test
    void holesFailBeforeExecutionOrTransactionStartButBoundNullIsValid() throws Exception {
        List<String> sent = new ArrayList<>();
        try (var c = recordingConnection(sent); var p = c.prepareStatement("SELECT ?, ?")) {
            c.setAutoCommit(false);
            p.setInt(2, 2);
            assertThrows(SQLException.class, p::execute);
            assertThrows(SQLException.class, p::executeQuery);
            assertThrows(SQLException.class, p::executeUpdate);
            assertThrows(SQLException.class, p::addBatch);
            assertTrue(sent.isEmpty(), "invalid bindings must not even start a transaction");
            p.setNull(1, Types.INTEGER);
            p.execute();
            p.execute(); // Bindings remain usable until explicitly changed or cleared.
            assertEquals(List.of("BEGIN TRANSACTION", "SELECT /**/NULL/**/, /**/2/**/",
                    "SELECT /**/NULL/**/, /**/2/**/"), sent);
            sent.clear();
            p.clearParameters();
            p.setObject(1, null);
            assertThrows(SQLException.class, p::execute);
            assertTrue(sent.isEmpty());
            p.setInt(2, 3);
            p.execute();
            assertEquals(List.of("SELECT /**/NULL/**/, /**/3/**/"), sent);
        }
    }

    @Test
    void metadataDefaultsAndRejectedBatchesDoNotBindOrCorruptSnapshots() throws Exception {
        List<String> sent = new ArrayList<>();
        try (var c = recordingConnection(sent); var p = c.prepareStatement("SELECT ?, ?")) {
            p.setInt(2, 2);
            p.getMetaData();
            assertEquals(List.of("SELECT * FROM (SELECT /**/NULL/**/, /**/2/**/) LIMIT 0"), sent);
            sent.clear();
            assertThrows(SQLException.class, p::execute);
            assertThrows(SQLException.class, p::addBatch);
        }
        try (var c = recordingConnection(sent); var p = c.prepareStatement("UPDATE t SET a = ?, b = ?")) {
            p.setInt(2, 2);
            assertThrows(SQLException.class, p::addBatch);
            p.setInt(1, 1);
            p.addBatch();
            p.clearParameters();
            p.setInt(2, 4);
            assertThrows(SQLException.class, p::addBatch);
            p.setObject(1, null);
            p.addBatch();
            p.clearParameters();
            assertEquals(2, p.executeBatch().length);
            assertEquals(List.of("UPDATE t SET a = /**/1/**/, b = /**/2/**/",
                    "UPDATE t SET a = /**/NULL/**/, b = /**/4/**/"), sent);
            assertEquals(0, p.executeBatch().length);
            assertThrows(SQLException.class, p::execute);
        }
        try (var c = recordingConnection(sent); var p = c.prepareStatement("UPDATE t SET a = '?' -- ?")) {
            assertEquals(0, p.getParameterMetaData().getParameterCount());
            assertThrows(SQLException.class, () -> p.setInt(1, 1));
            p.execute();
            p.clearParameters();
            p.addBatch();
            assertEquals(1, p.executeBatch().length);
        }
    }

    @Test
    void settersRejectIndicesBeforeConsumingStreamsOrArrays() throws Exception {
        InputStream stream = new InputStream() {
            @Override public int read() { throw new AssertionError("invalid setter consumed stream"); }
        };
        Reader reader = new Reader() {
            @Override public int read(char[] target, int offset, int length) { throw new AssertionError("invalid setter consumed reader"); }
            @Override public void close() { }
        };
        Array array = (Array) Proxy.newProxyInstance(Array.class.getClassLoader(), new Class<?>[]{Array.class},
                (proxy, method, args) -> { throw new AssertionError("invalid setter accessed array"); });
        try (var p = new QuackPreparedStatement(null, "SELECT ?, ?")) {
            List<ParameterOperation> setters = List.of(i -> { p.setInt(i, 1); return null; },
                    i -> { p.setNull(i, Types.INTEGER); return null; },
                    i -> { p.setObject(i, null); return null; },
                    i -> { p.setString(i, "value"); return null; },
                    i -> { p.setArray(i, array); return null; },
                    i -> { p.setAsciiStream(i, stream); return null; },
                    i -> { p.setAsciiStream(i, stream, 1); return null; },
                    i -> { p.setAsciiStream(i, stream, 1L); return null; },
                    i -> { p.setBinaryStream(i, stream); return null; },
                    i -> { p.setBinaryStream(i, stream, 1); return null; },
                    i -> { p.setBinaryStream(i, stream, 1L); return null; },
                    i -> { p.setCharacterStream(i, reader); return null; },
                    i -> { p.setCharacterStream(i, reader, 1); return null; },
                    i -> { p.setCharacterStream(i, reader, 1L); return null; });
            for (int index : new int[]{-1, 0, 3, Integer.MAX_VALUE}) {
                for (ParameterOperation setter : setters) assertThrows(SQLException.class, () -> setter.apply(index));
            }
            p.setNull(1, Types.INTEGER);
            p.setInt(2, 2);
            p.close();
            for (ParameterOperation setter : setters) assertThrows(SQLException.class, () -> setter.apply(1));
        }
    }

    @Test
    void everyIndexedParameterMetadataMethodChecksItsBounds() throws Exception {
        for (int count : new int[]{0, 2}) {
            ParameterMetaData metadata = new QuackParameterMetaData(count);
            List<ParameterOperation> operations = List.of(metadata::isNullable, metadata::isSigned,
                    metadata::getPrecision, metadata::getScale, metadata::getParameterType,
                    metadata::getParameterTypeName, metadata::getParameterClassName, metadata::getParameterMode);
            for (int index : new int[]{-1, 0, count + 1, Integer.MAX_VALUE}) {
                for (ParameterOperation operation : operations) assertThrows(SQLException.class, () -> operation.apply(index));
            }
            for (int index = 1; index <= count; index++) {
                assertEquals(List.of(ParameterMetaData.parameterNullableUnknown, false, 0, 0, Types.OTHER,
                                "OTHER", Object.class.getName(), ParameterMetaData.parameterModeIn),
                        List.of(metadata.isNullable(index), metadata.isSigned(index), metadata.getPrecision(index),
                                metadata.getScale(index), metadata.getParameterType(index), metadata.getParameterTypeName(index),
                                metadata.getParameterClassName(index), metadata.getParameterMode(index)));
            }
        }
    }

    @FunctionalInterface
    private interface ParameterOperation {
        Object apply(int index) throws SQLException;
    }

    private static QuackConnection recordingConnection(List<String> sent) {
        return new QuackConnection(QuackUri.parse("jdbc:quack://example.test"), uri -> request -> {
            if (request instanceof QuackMessage.ConnectionRequest) {
                return new QuackMessage.ConnectionResponse(
                        MessageHeader.of(MessageType.CONNECTION_RESPONSE).withConnectionId("bindings"),
                        Optional.empty(), Optional.empty(), Optional.empty());
            }
            if (request instanceof QuackMessage.PrepareRequest prepare) {
                sent.add(prepare.sql());
                if (prepare.sql().startsWith("UPDATE")) {
                    LogicalType countType = LogicalType.of(LogicalTypeId.BIGINT);
                    DataChunk count = new DataChunk(1, List.of(countType),
                            List.of(new DecodedVector.LongVec(countType, new long[]{1}, null)));
                    return new QuackMessage.PrepareResponse(MessageHeader.of(MessageType.PREPARE_RESPONSE),
                            List.of(countType), List.of("Count"), false, List.of(count), new HugeIntParts(0, 0));
                }
                return new QuackMessage.PrepareResponse(MessageHeader.of(MessageType.PREPARE_RESPONSE),
                        List.of(), List.of(), false, List.of(), new HugeIntParts(0, 0));
            }
            return new QuackMessage.SuccessResponse(MessageHeader.of(MessageType.SUCCESS_RESPONSE));
        });
    }

    @Test
    void countsOnlyMarkersOutsideDuckDbQuotedAndCommentedText() throws Exception {
        String[] queries = {
                "SELECT ? -- ? '",
                "SELECT -- ?\r ? -- ?\n",
                "SELECT /* ? /* nested ? */ ' */ ?",
                "SELECT 'it''s ?', ? AS \"a\"\"?\"",
                "SELECT E'it\\'s ?', ?",
                "SELECT E'one' -- ?\n 'two\\'?', ?",
                "SELECT $$ ' /* ? */ $$, ?",
                "SELECT $tag_1$ $other$ ? $tag_1$, ?",
                "SELECT $\u03b1$ ? $\u03b1$, ?",
                "SELECT foo$tag$, ?",
                "SELECT nameE'\\', ?",
                "SELECT U&'d\\0061t?', ? AS U&\"col?\"",
                "SELECT ?, 'unterminated ?",
                "SELECT ?, /* unterminated ?",
                "SELECT ?, $tag$ unterminated ?"
        };
        for (String sql : queries) {
            try (var statement = new QuackPreparedStatement(null, sql)) {
                assertEquals(1, statement.getParameterMetaData().getParameterCount(), sql);
            }
        }
    }

    @Test
    void executionBatchAndMetadataUseTheSameMarkerPositions() throws Exception {
        List<String> sent = new ArrayList<>();
        try (var c = recordingConnection(sent); var statement = c.prepareStatement("SELECT -? /* ? ' */")) {
            statement.setInt(1, -1);
            statement.execute();
            statement.addBatch();
            statement.setInt(1, -2);
            statement.addBatch();
            statement.executeBatch();
            statement.clearParameters();
            statement.getMetaData();
        }
        assertEquals(List.of(
                "SELECT -/**/(-1)/**/ /* ? ' */",
                "SELECT -/**/(-1)/**/ /* ? ' */",
                "SELECT -/**/(-2)/**/ /* ? ' */",
                "SELECT * FROM (SELECT -/**/NULL/**/ /* ? ' */) LIMIT 0"), sent);
    }
}

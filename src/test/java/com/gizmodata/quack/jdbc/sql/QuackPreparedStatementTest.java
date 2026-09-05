package com.gizmodata.quack.jdbc.sql;

import com.gizmodata.quack.jdbc.codec.HugeIntParts;
import com.gizmodata.quack.jdbc.message.MessageHeader;
import com.gizmodata.quack.jdbc.message.MessageType;
import com.gizmodata.quack.jdbc.message.QuackMessage;
import com.gizmodata.quack.jdbc.transport.QuackUri;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

class QuackPreparedStatementTest {
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
        try (var c = new QuackConnection(QuackUri.parse("jdbc:quack://example.test"), uri -> request -> {
            if (request instanceof QuackMessage.ConnectionRequest) {
                return new QuackMessage.ConnectionResponse(
                        MessageHeader.of(MessageType.CONNECTION_RESPONSE).withConnectionId("scanner"),
                        Optional.empty(), Optional.empty(), Optional.empty());
            }
            if (request instanceof QuackMessage.PrepareRequest prepare) {
                sent.add(prepare.sql());
                return new QuackMessage.PrepareResponse(MessageHeader.of(MessageType.PREPARE_RESPONSE),
                        List.of(), List.of(), false, List.of(), new HugeIntParts(0, 0));
            }
            return new QuackMessage.SuccessResponse(MessageHeader.of(MessageType.SUCCESS_RESPONSE));
        }); var statement = c.prepareStatement("SELECT -? /* ? ' */")) {
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

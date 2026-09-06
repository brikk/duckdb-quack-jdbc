package com.gizmodata.quack.jdbc.sql;

import com.gizmodata.quack.jdbc.QuackException;
import com.gizmodata.quack.jdbc.QuackProtocolException;
import com.gizmodata.quack.jdbc.message.DataChunk;
import com.gizmodata.quack.jdbc.type.LogicalTypeId;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class QuackStatement extends SkeletalStatement {

    private final QuackConnection connection;
    private final List<String> batch = new ArrayList<>();
    private QuackResultSet currentResultSet;
    private int updateCount = -1;
    private boolean closed;

    public QuackStatement(QuackConnection connection) {
        this.connection = connection;
    }

    @Override
    public void addBatch(String sql) throws SQLException {
        checkOpen();
        batch.add(sql);
    }

    @Override
    public void clearBatch() throws SQLException {
        checkOpen();
        batch.clear();
    }

    @Override
    public int[] executeBatch() throws SQLException {
        checkOpen();
        resetExecutionState();
        int[] counts = new int[batch.size()];
        for (int i = 0; i < batch.size(); i++) {
            try {
                counts[i] = executeUpdate(batch.get(i));
            } catch (SQLException e) {
                batch.clear();
                throw new java.sql.BatchUpdateException(e.getMessage(), e.getSQLState(), e.getErrorCode(),
                        Arrays.copyOf(counts, i), e);
            }
        }
        batch.clear();
        return counts;
    }

    @Override
    public void cancel() {
        // Quack protocol has no in-flight cancel today. Treat as best-effort
        // no-op so tools like DBeaver / DataGrip query-timeout buttons don't
        // see an exception. A real cancel will follow when the protocol
        // adds support.
    }

    @Override
    public Connection getConnection() {
        return connection;
    }

    /** Result-kind checks happen after server execution, not as pre-execution SQL validation. */
    @Override
    public ResultSet executeQuery(String sql) throws SQLException {
        execute(sql);
        if (currentResultSet == null) {
            throw new SQLException("Executed statement did not produce a ResultSet");
        }
        return currentResultSet;
    }

    /** A mismatch is reported after execution; this does not prevent server-side effects. */
    @Override
    public int executeUpdate(String sql) throws SQLException {
        execute(sql);
        if (currentResultSet != null) {
            resetExecutionState();
            throw new SQLException("Executed statement produced a ResultSet, not an update count");
        }
        return updateCount < 0 ? 0 : updateCount;
    }

    @Override
    public boolean execute(String sql) throws SQLException {
        checkOpen();
        resetExecutionState();
        connection.beginTransactionIfNeeded();
        QuackSession.Cursor cursor = null;
        try {
            cursor = connection.session().cursor(sql);
            if (cursor.resultKind().isPresent()) {
                switch (cursor.resultKind().get()) {
                    case CHANGED_ROWS -> {
                        updateCount = readChangedRows(cursor);
                        return false;
                    }
                    case NOTHING -> {
                        updateCount = 0;
                        return false;
                    }
                    case QUERY -> {
                        currentResultSet = new QuackResultSet(this, cursor);
                        cursor = null; // Ownership passes to the ResultSet.
                        return true;
                    }
                }
            }
            // Only explicit legacy sessions can reach the ambiguous v1 heuristic.
            DataChunk first = cursor.peekFirstChunk();
            if (looksLikeRowsAffected(cursor, first)) {
                updateCount = extractRowsAffected(first);
                currentResultSet = null;
                return false;
            }
            updateCount = -1;
            currentResultSet = new QuackResultSet(this, cursor);
            cursor = null;
            return true;
        } catch (RuntimeException e) {
            if (e instanceof QuackException) {
                throw new SQLException(e.getMessage(), e);
            }
            throw new SQLException("Failed to execute SQL: " + sql, e);
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    private int readChangedRows(QuackSession.Cursor cursor) {
        String invalid = "Invalid changed_rows result: expected exactly one nonnegative BIGINT count";
        if (cursor.columnNames().size() != 1 || cursor.columnTypes().size() != 1
                || cursor.columnTypes().get(0).id() != LogicalTypeId.BIGINT) {
            throw new QuackProtocolException(invalid);
        }
        Long count = null;
        DataChunk chunk;
        while ((chunk = cursor.nextChunk()) != null) {
            if (chunk.rowCount() < 0 || chunk.rowCount() > 1
                    || chunk.columns().size() != 1 || chunk.types().size() != 1
                    || chunk.types().get(0).id() != LogicalTypeId.BIGINT
                    || chunk.columns().get(0).type().id() != LogicalTypeId.BIGINT
                    || chunk.columns().get(0).size() != chunk.rowCount()) {
                throw new QuackProtocolException(invalid);
            }
            if (chunk.rowCount() == 0) continue;
            if (count != null) throw new QuackProtocolException(invalid);
            Object value = chunk.columns().get(0).getObject(0);
            if (!(value instanceof Long number) || number < 0) throw new QuackProtocolException(invalid);
            count = number;
        }
        if (count == null) throw new QuackProtocolException(invalid);
        return (int) Math.min(count, Integer.MAX_VALUE);
    }

    private boolean looksLikeRowsAffected(QuackSession.Cursor cursor, DataChunk first) {
        if (cursor.columnNames().size() != 1) return false;
        String name = cursor.columnNames().get(0);
        if (!"Count".equalsIgnoreCase(name) && !"rows_affected".equalsIgnoreCase(name)) {
            return false;
        }
        if (cursor.columnTypes().isEmpty()) return false;
        LogicalTypeId id = cursor.columnTypes().get(0).id();
        return (id == LogicalTypeId.BIGINT || id == LogicalTypeId.INTEGER
                || id == LogicalTypeId.UBIGINT || id == LogicalTypeId.UINTEGER)
                && first != null
                && first.rowCount() <= 1;
    }

    private int extractRowsAffected(DataChunk chunk) {
        if (chunk == null || chunk.rowCount() == 0 || chunk.columns().isEmpty()) return 0;
        Object value = chunk.columns().get(0).getObject(0);
        if (value instanceof Number n) {
            long v = n.longValue();
            return v > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) v;
        }
        return 0;
    }

    @Override
    public ResultSet getResultSet() {
        return currentResultSet;
    }

    @Override
    public int getUpdateCount() {
        return updateCount;
    }

    /**
     * Advance past the current result. Quack executes exactly one statement
     * per {@link #execute(String)}, so there is never a second result: this
     * closes the current {@link ResultSet} (if any) and clears the update
     * count. Per the JDBC contract, callers detect end-of-results when
     * {@code getMoreResults() == false && getUpdateCount() == -1}; without
     * resetting the count here, tools like DataGrip / DBeaver that drain
     * results in a loop after an INSERT/UPDATE/DELETE would spin forever
     * because {@link #getUpdateCount()} would keep reporting the affected-row
     * count instead of {@code -1}.
     */
    @Override
    public boolean getMoreResults() {
        resetExecutionState();
        return false;
    }

    /** Discard the previous execution before transaction start or parameter validation can fail. */
    protected void resetExecutionState() {
        if (currentResultSet != null) {
            currentResultSet.close();
            currentResultSet = null;
        }
        updateCount = -1;
    }

    @Override
    public boolean getMoreResults(int current) {
        // KEEP_CURRENT_RESULT / CLOSE_ALL_RESULTS only matter with multiple
        // open result sets, which Quack never produces. Behave like the
        // no-arg form so the drain loop terminates.
        return getMoreResults();
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        if (currentResultSet != null) {
            currentResultSet.close();
        }
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    protected void checkOpen() throws SQLException {
        if (closed) throw new SQLException("Statement is closed");
    }
}

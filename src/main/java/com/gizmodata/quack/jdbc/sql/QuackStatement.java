package com.gizmodata.quack.jdbc.sql;

import com.gizmodata.quack.jdbc.QuackException;
import com.gizmodata.quack.jdbc.QuackCancelledException;
import com.gizmodata.quack.jdbc.message.DataChunk;
import com.gizmodata.quack.jdbc.type.LogicalTypeId;

import java.math.BigInteger;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class QuackStatement extends SkeletalStatement {

    private final QuackConnection connection;
    private final List<String> batch = new ArrayList<>();
    private QuackResultSet currentResultSet;
    private int updateCount = -1;
    private boolean closed;
    private volatile QuackSession.Query activeQuery;

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
    public void cancel() throws SQLException {
        checkOpen();
        if (connection.session().protocolVersion() != 3) {
            throw new SQLFeatureNotSupportedException("Statement cancellation requires Quack v3");
        }
        QuackSession.Query query = activeQuery;
        if (query == null) return;
        try {
            connection.session().cancel(query);
        } catch (RuntimeException e) {
            throw new SQLException(e.getMessage(), e);
        }
    }

    @Override
    public Connection getConnection() {
        return connection;
    }

    @Override
    public ResultSet executeQuery(String sql) throws SQLException {
        execute(sql);
        if (currentResultSet == null) {
            throw new SQLException("Query did not produce a ResultSet: " + sql);
        }
        return currentResultSet;
    }

    @Override
    public int executeUpdate(String sql) throws SQLException {
        execute(sql);
        return updateCount < 0 ? 0 : updateCount;
    }

    @Override
    public boolean execute(String sql) throws SQLException {
        checkOpen();
        resetExecutionState();
        QuackSession.Query query = connection.session().newQuery();
        activeQuery = query;
        try {
            query.checkCancelled();
            connection.beginTransactionIfNeeded();
            QuackSession.Cursor cursor = connection.session().cursor(sql, query);
            DataChunk first = cursor.peekFirstChunk();
            boolean countResult = looksLikeRowsAffected(sql, cursor, first);
            query.checkCancelled();
            if (countResult) {
                updateCount = extractRowsAffected(first);
                cursor.close();
                currentResultSet = null;
                return false;
            }
            updateCount = -1;
            currentResultSet = new QuackResultSet(this, cursor);
            return true;
        } catch (RuntimeException e) {
            query.done = true;
            if (e instanceof QuackCancelledException) {
                throw new SQLException(e.getMessage(), "57014", e);
            }
            if (e instanceof QuackException) {
                throw new SQLException(e.getMessage(), e);
            }
            throw new SQLException("Failed to execute SQL: " + sql, e);
        } catch (SQLException e) {
            query.done = true;
            throw e;
        }
    }

    private boolean looksLikeRowsAffected(String sql, QuackSession.Cursor cursor, DataChunk first) {
        if (cursor.columnNames().size() != 1 || cursor.columnTypes().size() != 1
                || first == null || first.rowCount() != 1 || first.columns().size() != 1
                || cursor.materializedRowCount() != 1) return false;
        String name = cursor.columnNames().get(0);
        boolean legacyCount = "Count".equalsIgnoreCase(name) || "rows_affected".equalsIgnoreCase(name);
        if (!legacyCount && !"count_star()".equalsIgnoreCase(name)) return false;
        LogicalTypeId id = cursor.columnTypes().get(0).id();
        if (id != LogicalTypeId.BIGINT && id != LogicalTypeId.INTEGER
                && id != LogicalTypeId.UBIGINT && id != LogicalTypeId.UINTEGER) return false;
        Object value = first.columns().get(0).getObject(0);
        if (!(value instanceof Number number)
                || (value instanceof BigInteger big ? big.signum() < 0 : number.longValue() < 0)) return false;
        // The wire has no statement-kind tag. Labels alone also occur in SELECT
        // and RETURNING results, which must never be consumed as update counts.
        SqlResultKind kind = SqlResultKind.of(sql);
        return kind == SqlResultKind.DML_COUNT || legacyCount && kind == SqlResultKind.OTHER_COUNT;
    }

    private int extractRowsAffected(DataChunk chunk) {
        if (chunk == null || chunk.rowCount() == 0 || chunk.columns().isEmpty()) return 0;
        Object value = chunk.columns().get(0).getObject(0);
        if (value instanceof BigInteger big) {
            return big.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0 ? Integer.MAX_VALUE : big.intValue();
        }
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

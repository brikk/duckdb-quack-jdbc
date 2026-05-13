package com.gizmodata.quack.jdbc.sql;

import com.gizmodata.quack.jdbc.QuackException;
import com.gizmodata.quack.jdbc.type.LogicalTypeId;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
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
        int[] counts = new int[batch.size()];
        for (int i = 0; i < batch.size(); i++) {
            try {
                counts[i] = executeUpdate(batch.get(i));
            } catch (SQLException e) {
                counts[i] = java.sql.Statement.EXECUTE_FAILED;
                batch.clear();
                throw new java.sql.BatchUpdateException(e.getMessage(), counts, e);
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
        try {
            QuackSession.PreparedResult result = connection.session().prepare(sql);
            if (looksLikeRowsAffected(result)) {
                updateCount = extractRowsAffected(result);
                currentResultSet = null;
                return false;
            }
            updateCount = -1;
            currentResultSet = new QuackResultSet(this, result);
            return true;
        } catch (RuntimeException e) {
            if (e instanceof QuackException) {
                throw new SQLException(e.getMessage(), e);
            }
            throw new SQLException("Failed to execute SQL: " + sql, e);
        }
    }

    private boolean looksLikeRowsAffected(QuackSession.PreparedResult result) {
        if (result.columnNames().size() != 1) return false;
        String name = result.columnNames().get(0);
        if (!"Count".equalsIgnoreCase(name) && !"rows_affected".equalsIgnoreCase(name)) {
            return false;
        }
        if (result.columnTypes().isEmpty()) return false;
        LogicalTypeId id = result.columnTypes().get(0).id();
        return id == LogicalTypeId.BIGINT || id == LogicalTypeId.INTEGER
                || id == LogicalTypeId.UBIGINT || id == LogicalTypeId.UINTEGER;
    }

    private int extractRowsAffected(QuackSession.PreparedResult result) {
        if (result.chunks().isEmpty()) return 0;
        var chunk = result.chunks().get(0);
        if (chunk.rowCount() == 0 || chunk.columns().isEmpty()) return 0;
        Object value = chunk.columns().get(0).values().get(0);
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

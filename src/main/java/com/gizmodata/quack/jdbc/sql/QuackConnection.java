package com.gizmodata.quack.jdbc.sql;

import com.gizmodata.quack.jdbc.QuackException;
import com.gizmodata.quack.jdbc.transport.QuackUri;

import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;

public final class QuackConnection extends SkeletalConnection {

    private final QuackUri uri;
    private final QuackSession session;
    private boolean autoCommit = true;
    private String catalog;
    private String schema;
    private volatile boolean closed;

    public QuackConnection(QuackUri uri) {
        this.uri = uri;
        try {
            this.session = QuackSession.connect(uri);
        } catch (RuntimeException e) {
            throw new QuackException(e.getMessage(), e);
        }
        this.catalog = uri.database().orElse(null);
    }

    QuackSession session() {
        return session;
    }

    QuackUri uri() {
        return uri;
    }

    @Override
    public Statement createStatement() throws SQLException {
        checkOpen();
        return new QuackStatement(this);
    }

    @Override
    public PreparedStatement prepareStatement(String sql) throws SQLException {
        checkOpen();
        return new QuackPreparedStatement(this, sql);
    }

    @Override
    public void setAutoCommit(boolean autoCommit) {
        this.autoCommit = autoCommit;
    }

    @Override
    public boolean getAutoCommit() {
        return autoCommit;
    }

    @Override
    public void commit() throws SQLException {
        checkOpen();
        if (!autoCommit) {
            try (Statement s = createStatement()) {
                s.execute("COMMIT");
            }
        }
    }

    @Override
    public void rollback() throws SQLException {
        checkOpen();
        if (!autoCommit) {
            try (Statement s = createStatement()) {
                s.execute("ROLLBACK");
            }
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try {
            session.close();
        } catch (RuntimeException ignored) {
            // best effort
        }
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public DatabaseMetaData getMetaData() {
        return new QuackDatabaseMetaData(this);
    }

    @Override
    public String getCatalog() {
        return catalog;
    }

    @Override
    public void setCatalog(String catalog) throws SQLException {
        if (catalog != null && !catalog.isEmpty() && !catalog.equals(this.catalog)) {
            try (Statement s = createStatement()) {
                s.execute("USE " + quoteIdent(catalog)
                        + (schema != null && !schema.isEmpty() ? "." + quoteIdent(schema) : ""));
            }
        }
        this.catalog = catalog;
    }

    @Override
    public String getSchema() {
        return schema;
    }

    @Override
    public void setSchema(String schema) throws SQLException {
        if (schema != null && !schema.isEmpty() && !schema.equals(this.schema)) {
            try (Statement s = createStatement()) {
                String catPrefix = catalog != null && !catalog.isEmpty()
                        ? quoteIdent(catalog) + "."
                        : "";
                s.execute("USE " + catPrefix + quoteIdent(schema));
            }
        }
        this.schema = schema;
    }

    private static String quoteIdent(String ident) {
        return "\"" + ident.replace("\"", "\"\"") + "\"";
    }

    private void checkOpen() throws SQLException {
        if (closed) {
            throw new SQLException("Connection is closed");
        }
    }
}

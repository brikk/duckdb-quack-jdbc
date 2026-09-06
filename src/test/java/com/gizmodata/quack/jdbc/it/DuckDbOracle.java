package com.gizmodata.quack.jdbc.it;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;

public final class DuckDbOracle {

    public static final String DRIVER_CLASS = "org.duckdb.DuckDBDriver";

    private DuckDbOracle() {
    }

    public static boolean available() {
        return available(QuackServerFixture.required(), DuckDbOracle.class.getClassLoader());
    }

    static boolean available(boolean required, ClassLoader loader) {
        try {
            Class.forName(DRIVER_CLASS, true, loader);
            return true;
        } catch (ClassNotFoundException e) {
            if (required) {
                throw new IllegalStateException("Required integration tests need the DuckDB JDBC oracle; enable -Poracle", e);
            }
            return false;
        }
    }

    public static Connection newConnection() throws SQLException {
        return DriverManager.getConnection("jdbc:duckdb:");
    }

    public record ColumnType(String typeName, int type) {
    }

    public static ColumnType columnType(Connection connection, String sql) throws SQLException {
        try (Statement s = connection.createStatement();
             ResultSet rs = s.executeQuery(sql)) {
            ResultSetMetaData md = rs.getMetaData();
            return new ColumnType(md.getColumnTypeName(1), md.getColumnType(1));
        }
    }
}

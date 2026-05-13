package com.gizmodata.quack.jdbc.codec;

public final class QuackConstants {
    public static final long QUACK_VERSION = 1L;
    public static final int DEFAULT_QUACK_PORT = 9494;
    public static final String QUACK_ENDPOINT = "/quack";
    public static final String DUCKDB_MIME_TYPE = "application/duckdb";
    public static final int FIELD_END = 0xFFFF;
    public static final long OPTIONAL_INDEX_INVALID = -1L;

    private QuackConstants() {
    }
}

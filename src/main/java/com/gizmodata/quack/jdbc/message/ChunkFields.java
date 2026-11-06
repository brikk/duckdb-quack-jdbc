package com.gizmodata.quack.jdbc.message;

import com.gizmodata.quack.jdbc.codec.WireField;

/**
 * Field ids of DuckDB's DataChunk and Vector serialization, one enum per wire
 * object. Vector fields past the validity mask depend on the vector's
 * physical type, so each variable-width layout has its own enum.
 */
public final class ChunkFields {

    private ChunkFields() {
    }

    /** The object wrapping a DataChunk pointer in message bodies. */
    public enum ChunkWrapper implements WireField {
        DATA_CHUNK(300);

        private final int id;
        ChunkWrapper(int id) { this.id = id; }
        @Override public int id() { return id; }
    }

    public enum DataChunk implements WireField {
        ROW_COUNT(100), TYPES(101), COLUMNS(102);

        private final int id;
        DataChunk(int id) { this.id = id; }
        @Override public int id() { return id; }
    }

    /** Fields shared by every vector encoding; DATA carries fixed-width payloads and v1 string lists. */
    public enum Vector implements WireField {
        VECTOR_TYPE(90),
        /** Leading field DuckDB writes for GEOMETRY vectors; read and discarded. */
        GEOMETRY_HEADER(99),
        HAS_VALIDITY(100), VALIDITY(101), DATA(102);

        private final int id;
        Vector(int id) { this.id = id; }
        @Override public int id() { return id; }
    }

    public enum DictionaryVector implements WireField {
        SELECTION(91), DICTIONARY_COUNT(92);

        private final int id;
        DictionaryVector(int id) { this.id = id; }
        @Override public int id() { return id; }
    }

    public enum SequenceVector implements WireField {
        START(91), INCREMENT(92);

        private final int id;
        SequenceVector(int id) { this.id = id; }
        @Override public int id() { return id; }
    }

    /** v3 string layout: one length table and one byte blob instead of a list. */
    public enum VarcharVectorV3 implements WireField {
        BYTE_COUNT(107), LENGTHS(108), BYTES(109);

        private final int id;
        VarcharVectorV3(int id) { this.id = id; }
        @Override public int id() { return id; }
    }

    public enum StructVector implements WireField {
        CHILDREN(103);

        private final int id;
        StructVector(int id) { this.id = id; }
        @Override public int id() { return id; }
    }

    public enum ListVector implements WireField {
        LIST_SIZE(104), ENTRIES(105), CHILD(106);

        private final int id;
        ListVector(int id) { this.id = id; }
        @Override public int id() { return id; }
    }

    public enum ArrayVector implements WireField {
        ARRAY_SIZE(103), CHILD(104);

        private final int id;
        ArrayVector(int id) { this.id = id; }
        @Override public int id() { return id; }
    }

    public enum ListEntry implements WireField {
        OFFSET(100), LENGTH(101);

        private final int id;
        ListEntry(int id) { this.id = id; }
        @Override public int id() { return id; }
    }
}

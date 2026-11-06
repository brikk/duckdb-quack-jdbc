package com.gizmodata.quack.jdbc.codec;

/**
 * A field id inside one serialized wire object. Ids are only unique within
 * their object, so each object's fields live in their own enum; the enum
 * name is used in protocol error messages.
 */
public interface WireField {

    int id();

    String name();

    default String describe() {
        return name() + "(" + id() + ")";
    }
}

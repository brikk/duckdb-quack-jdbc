package com.gizmodata.quack.jdbc;

/** A caller explicitly cancelled this query; buffered results are no longer consumable. */
public final class QuackCancelledException extends QuackException {
    public QuackCancelledException() {
        super("Interrupted: query was cancelled");
    }

    public QuackCancelledException(Throwable cause) {
        super("Interrupted: query was cancelled", cause);
    }
}

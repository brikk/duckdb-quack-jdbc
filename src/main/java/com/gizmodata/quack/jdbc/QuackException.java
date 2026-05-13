package com.gizmodata.quack.jdbc;

public class QuackException extends RuntimeException {
    public QuackException(String message) {
        super(message);
    }

    public QuackException(String message, Throwable cause) {
        super(message, cause);
    }
}

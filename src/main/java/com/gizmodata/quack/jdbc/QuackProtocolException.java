package com.gizmodata.quack.jdbc;

public class QuackProtocolException extends QuackException {
    public QuackProtocolException(String message) {
        super(message);
    }

    public QuackProtocolException(String message, Throwable cause) {
        super(message, cause);
    }
}

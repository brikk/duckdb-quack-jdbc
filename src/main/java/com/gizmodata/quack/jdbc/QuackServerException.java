package com.gizmodata.quack.jdbc;

public class QuackServerException extends QuackException {
    private final String serverMessage;

    public QuackServerException(String serverMessage) {
        super(serverMessage);
        this.serverMessage = serverMessage;
    }

    public String getServerMessage() {
        return serverMessage;
    }
}

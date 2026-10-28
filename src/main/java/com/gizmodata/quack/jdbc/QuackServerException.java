package com.gizmodata.quack.jdbc;

import java.util.Map;

public class QuackServerException extends QuackException {
    private final String serverMessage;
    private final String exceptionType;
    private final Map<String, String> extraInfo;
    private final boolean mustInvalidate;

    public QuackServerException(String serverMessage) {
        this(serverMessage, "", Map.of(), false);
    }

    public QuackServerException(String serverMessage, String exceptionType,
                                Map<String, String> extraInfo, boolean mustInvalidate) {
        super(serverMessage);
        this.serverMessage = serverMessage;
        this.exceptionType = exceptionType;
        this.extraInfo = Map.copyOf(extraInfo);
        this.mustInvalidate = mustInvalidate;
    }

    public String getServerMessage() {
        return serverMessage;
    }

    public String getExceptionType() {
        return exceptionType;
    }

    public Map<String, String> getExtraInfo() {
        return extraInfo;
    }

    /** True only when the server explicitly reports that its database is unusable. */
    public boolean mustInvalidate() {
        return mustInvalidate;
    }
}

package com.gizmodata.quack.jdbc.transport;

/** Creates a transport for a parsed Quack JDBC URL. */
@FunctionalInterface
public interface QuackTransportFactory {

    QuackTransport create(QuackUri uri);

    static QuackTransportFactory http() {
        return QuackHttpTransport::from;
    }
}

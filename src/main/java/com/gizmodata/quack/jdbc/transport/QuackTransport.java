package com.gizmodata.quack.jdbc.transport;

import com.gizmodata.quack.jdbc.message.QuackMessage;

/** Transport capable of sending one Quack request and returning its response. */
public interface QuackTransport {

    QuackMessage send(QuackMessage request);

    /** Called after the handshake, before any version-dependent requests. */
    default void setProtocolVersion(long version) { }
}

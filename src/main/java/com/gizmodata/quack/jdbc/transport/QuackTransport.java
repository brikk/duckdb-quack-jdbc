package com.gizmodata.quack.jdbc.transport;

import com.gizmodata.quack.jdbc.message.QuackMessage;

/**
 * Transport capable of sending one Quack request and returning its response.
 * Implementations must support concurrent control traffic (heartbeat, cancel,
 * disconnect) while PREPARE or FETCH is waiting. Blocking I/O should cooperate
 * with interruption so a bounded session close can release transport resources.
 */
public interface QuackTransport {

    QuackMessage send(QuackMessage request);

    /** Called after the handshake, before any version-dependent requests. */
    default void setProtocolVersion(long version) { }
}

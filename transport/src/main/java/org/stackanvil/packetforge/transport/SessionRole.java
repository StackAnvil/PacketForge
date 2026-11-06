package org.stackanvil.packetforge.transport;

import org.stackanvil.packetforge.commons.PacketRegistry.Flow;

/** The endpoint determines the direction of every packet on the connection. */
public enum SessionRole {
    CLIENT(Flow.CLIENTBOUND, Flow.SERVERBOUND),
    SERVER(Flow.SERVERBOUND, Flow.CLIENTBOUND);

    private final Flow inbound;
    private final Flow outbound;

    SessionRole(Flow inbound, Flow outbound) {
        this.inbound = inbound;
        this.outbound = outbound;
    }

    public Flow inbound() {
        return inbound;
    }

    public Flow outbound() {
        return outbound;
    }
}

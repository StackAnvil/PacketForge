package org.stackanvil.packetforge.transport;

import org.stackanvil.packetforge.commons.Packet;
import org.stackanvil.packetforge.commons.PacketRegistry.State;
import org.stackanvil.packetforge.commons.SkippedPacketException;

/** Callbacks run in connection order on the network event loop. */
public interface SessionListener {
    default void connected(Session session) {}

    default void packetReceived(Session session, Packet packet) {}

    default void packetSent(Session session, Packet packet) {}

    default void stateChanged(Session session, State inbound, State outbound) {}

    default void packetSkipped(Session session, SkippedPacketException cause) {}

    default void disconnected(Session session, Throwable cause) {}
}

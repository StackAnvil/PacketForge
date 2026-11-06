package org.stackanvil.packetforge.transport.login;

import java.net.InetAddress;
import java.util.concurrent.CompletionStage;
import org.stackanvil.packetforge.protocol.type.external.com.mojang.authlib.GameProfile;

/** Verifies a join asynchronously. Return without blocking. A null result rejects an unverified account. The address may be null. */
@FunctionalInterface
public interface ServerAuthenticator {
    CompletionStage<GameProfile> hasJoinedServer(String username, String serverHash, InetAddress address);
}

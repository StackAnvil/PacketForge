package org.stackanvil.packetforge.commons;

import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;

public final class CryptoCodecs {
    private CryptoCodecs() {}

    public static final PacketCodec<PublicKey> PUBLIC_KEY =
            WireCodecs.mapped(
                    WireCodecs.byteArray(512),
                    bytes -> {
                        try {
                            return KeyFactory.getInstance("RSA")
                                    .generatePublic(new X509EncodedKeySpec(bytes));
                        } catch (java.security.GeneralSecurityException e) {
                            throw new IllegalArgumentException("Invalid RSA public key", e);
                        }
                    },
                    PublicKey::getEncoded);
}

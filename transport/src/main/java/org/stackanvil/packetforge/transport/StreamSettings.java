package org.stackanvil.packetforge.transport;

import java.security.GeneralSecurityException;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;

/** Mutations and cipher use are confined to one channel's event loop. */
final class StreamSettings {
    static final int MAX_FRAME = (1 << 21) - 1;
    static final int MAX_PACKET = 8 * 1024 * 1024;
    volatile int threshold = -1;
    volatile Ciphers ciphers;

    record Ciphers(Cipher decrypt, Cipher encrypt) {}

    Ciphers prepare(SecretKey key) {
        if (ciphers != null) throw new IllegalStateException("Encryption is already enabled");
        if (!"AES".equalsIgnoreCase(key.getAlgorithm()) || key.getEncoded() == null || key.getEncoded().length != 16)
            throw new IllegalArgumentException("Minecraft encryption requires a 16-byte AES key");
        try {
            Cipher decrypt = Cipher.getInstance("AES/CFB8/NoPadding");
            Cipher encrypt = Cipher.getInstance("AES/CFB8/NoPadding");
            var iv = new IvParameterSpec(key.getEncoded());
            decrypt.init(Cipher.DECRYPT_MODE, key, iv);
            encrypt.init(Cipher.ENCRYPT_MODE, key, iv);
            return new Ciphers(decrypt, encrypt);
        } catch (GeneralSecurityException invalid) {
            throw new IllegalArgumentException("Cannot initialize Minecraft encryption", invalid);
        }
    }
}

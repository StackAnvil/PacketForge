package org.stackanvil.packetforge.transport.login;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.List;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.stackanvil.packetforge.protocol.packet.login.ServerboundKeyPacket;
import org.stackanvil.packetforge.protocol.type.external.com.mojang.authlib.GameProfile;

/** The vanilla login key exchange. These operations never load Minecraft classes. */
public final class LoginCrypto {
    private LoginCrypto() {}

    public static GameProfile offlineProfile(String username) {
        validateUsername(username);
        return new GameProfile(
                UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8)),
                username,
                List.of());
    }

    static void validateUsername(String username) {
        if (username == null || username.length() > 16 || username.chars().anyMatch(c -> c <= 32 || c >= 127))
            throw new IllegalArgumentException("Invalid Minecraft username");
    }

    public static KeyPair generateKeyPair() throws GeneralSecurityException {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(1024);
        return generator.generateKeyPair();
    }

    public static SecretKey generateSecretKey() throws GeneralSecurityException {
        var generator = KeyGenerator.getInstance("AES");
        generator.init(128);
        return generator.generateKey();
    }

    public static PublicKey publicKey(byte[] encoded) throws GeneralSecurityException {
        return KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(encoded));
    }

    public static String serverHash(String serverId, PublicKey key, SecretKey secret) throws GeneralSecurityException {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        digest.update(serverId.getBytes(StandardCharsets.ISO_8859_1));
        digest.update(secret.getEncoded());
        digest.update(key.getEncoded());
        return new BigInteger(digest.digest()).toString(16);
    }

    public static ServerboundKeyPacket keyResponse(PublicKey key, SecretKey secret, byte[] challenge)
            throws GeneralSecurityException {
        return new ServerboundKeyPacket(
                rsa(Cipher.ENCRYPT_MODE, key, secret.getEncoded()), rsa(Cipher.ENCRYPT_MODE, key, challenge));
    }

    public static SecretKey sharedSecret(PrivateKey key, ServerboundKeyPacket packet, byte[] challenge)
            throws GeneralSecurityException {
        byte[] answer = rsa(Cipher.DECRYPT_MODE, key, packet.encryptedChallenge());
        if (!MessageDigest.isEqual(challenge, answer)) throw new GeneralSecurityException("Invalid login challenge");
        byte[] secret = rsa(Cipher.DECRYPT_MODE, key, packet.keybytes());
        if (secret.length != 16) throw new GeneralSecurityException("Login requires a 16-byte AES key");
        return new SecretKeySpec(secret, "AES");
    }

    private static byte[] rsa(int mode, Key key, byte[] bytes) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        cipher.init(mode, key);
        return cipher.doFinal(bytes);
    }
}

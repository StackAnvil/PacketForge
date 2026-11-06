package org.stackanvil.packetforge.transport.login;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigInteger;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.PublicKey;
import java.util.ArrayList;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.stackanvil.packetforge.protocol.packet.login.ServerboundKeyPacket;

class LoginCryptoTest {
    @Test
    void keyExchangeAgreesWithVanilla() throws Exception {
        var keys = LoginCrypto.generateKeyPair();
        var secret = LoginCrypto.generateSecretKey();
        byte[] challenge = {1, 3, 5, 7};
        var answer = LoginCrypto.keyResponse(keys.getPublic(), secret, challenge);
        assertArrayEquals(
                secret.getEncoded(),
                LoginCrypto.sharedSecret(keys.getPrivate(), answer, challenge).getEncoded());
        assertEquals(keys.getPublic(), LoginCrypto.publicKey(keys.getPublic().getEncoded()));
        try (var vanilla = vanilla()) {
            var crypt = vanilla.loadClass("net.minecraft.util.Crypt");
            byte[] nativeSecret = (byte[]) crypt.getMethod("decryptUsingKey", Key.class, byte[].class)
                    .invoke(null, keys.getPrivate(), answer.keybytes());
            byte[] nativeChallenge = (byte[]) crypt.getMethod("decryptUsingKey", Key.class, byte[].class)
                    .invoke(null, keys.getPrivate(), answer.encryptedChallenge());
            assertArrayEquals(secret.getEncoded(), nativeSecret);
            assertArrayEquals(challenge, nativeChallenge);
            var encrypt = crypt.getMethod("encryptUsingKey", Key.class, byte[].class);
            byte[] encryptedSecret = (byte[]) encrypt.invoke(null, keys.getPublic(), secret.getEncoded());
            byte[] encryptedChallenge = (byte[]) encrypt.invoke(null, keys.getPublic(), challenge);
            assertArrayEquals(
                    secret.getEncoded(),
                    LoginCrypto.sharedSecret(
                                    keys.getPrivate(),
                                    new ServerboundKeyPacket(encryptedSecret, encryptedChallenge),
                                    challenge)
                            .getEncoded());
            for (String server : new String[] {"", "server", "negative", "café", "😀"}) {
                byte[] nativeHash =
                        (byte[]) crypt.getMethod("digestData", String.class, PublicKey.class, SecretKey.class)
                                .invoke(null, server, keys.getPublic(), secret);
                assertEquals(
                        new BigInteger(nativeHash).toString(16),
                        LoginCrypto.serverHash(server, keys.getPublic(), secret));
            }
        }
    }

    @Test
    void invalidChallengesCiphertextAndKeyLengthsAreRejected() throws Exception {
        var keys = LoginCrypto.generateKeyPair();
        var secret = LoginCrypto.generateSecretKey();
        var answer = LoginCrypto.keyResponse(keys.getPublic(), secret, new byte[] {1, 2});
        assertThrows(
                GeneralSecurityException.class,
                () -> LoginCrypto.sharedSecret(keys.getPrivate(), answer, new byte[] {2, 1}));
        assertThrows(
                GeneralSecurityException.class,
                () -> LoginCrypto.sharedSecret(
                        keys.getPrivate(),
                        new ServerboundKeyPacket(new byte[] {1}, answer.encryptedChallenge()),
                        new byte[] {1, 2}));
        var wrongLength =
                LoginCrypto.keyResponse(keys.getPublic(), new SecretKeySpec(new byte[24], "AES"), new byte[] {1, 2});
        assertThrows(
                GeneralSecurityException.class,
                () -> LoginCrypto.sharedSecret(keys.getPrivate(), wrongLength, new byte[] {1, 2}));
        assertThrows(GeneralSecurityException.class, () -> LoginCrypto.publicKey(new byte[] {1, 2}));
    }

    @Test
    void offlineNamesAndIdsMatchTheSourceRules() throws Exception {
        try (var vanilla = vanilla()) {
            var uuid =
                    vanilla.loadClass("net.minecraft.core.UUIDUtil").getMethod("createOfflinePlayerUUID", String.class);
            var valid = vanilla.loadClass("net.minecraft.util.StringUtil").getMethod("isValidPlayerName", String.class);
            for (String name : new String[] {
                "",
                "Player",
                "PacketForge",
                "a".repeat(16),
                "a".repeat(17),
                "a b",
                "café",
                "a\n",
                "a\u007f",
                "!@#$%^&*()"
            }) {
                boolean accepted = (boolean) valid.invoke(null, name);
                if (accepted)
                    assertEquals(
                            uuid.invoke(null, name),
                            LoginCrypto.offlineProfile(name).id());
                else assertThrows(IllegalArgumentException.class, () -> LoginCrypto.offlineProfile(name));
            }
        }
    }

    private static URLClassLoader vanilla() throws Exception {
        Path cache = Path.of(System.getProperty("packetforge.cache"), System.getProperty("packetforge.version"));
        var urls = new ArrayList<URL>();
        urls.add(cache.resolve("minecraft.jar").toUri().toURL());
        try (var paths = Files.walk(cache.resolve("libraries"))) {
            for (Path jar : paths.filter(path -> path.toString().endsWith(".jar"))
                    .sorted()
                    .toList()) urls.add(jar.toUri().toURL());
        }
        return new URLClassLoader(urls.toArray(URL[]::new), ClassLoader.getPlatformClassLoader());
    }
}

package org.stackanvil.packetforge.transport.login;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.key.Key;

/** Cookie bytes are copied on both sides of the in-memory store. */
public interface CookieStore {
    Optional<byte[]> get(Key key);

    void put(Key key, byte[] value);

    static CookieStore inMemory() {
        return new CookieStore() {
            private final ConcurrentHashMap<Key, byte[]> cookies = new ConcurrentHashMap<>();

            public Optional<byte[]> get(Key key) {
                return Optional.ofNullable(cookies.get(key)).map(byte[]::clone);
            }

            public void put(Key key, byte[] value) {
                cookies.put(key, value.clone());
            }
        };
    }
}

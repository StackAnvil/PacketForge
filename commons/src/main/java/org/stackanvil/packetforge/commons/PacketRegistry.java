package org.stackanvil.packetforge.commons;

import io.netty.buffer.ByteBuf;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/** Immutable registry keyed by protocol state, direction, and numeric packet ID. */
public final class PacketRegistry {
    public enum State {
        HANDSHAKING,
        STATUS,
        LOGIN,
        CONFIGURATION,
        PLAY
    }

    public enum Flow {
        CLIENTBOUND,
        SERVERBOUND
    }

    public record Key(State state, Flow flow, int id) {
        public Key {
            Objects.requireNonNull(state);
            Objects.requireNonNull(flow);
            if (id < 0) throw new IllegalArgumentException("Negative packet ID");
        }
    }

    public record Entry<T extends Packet>(Key key, String name, Class<T> type, PacketCodec<T> codec) {
        public Entry {
            Objects.requireNonNull(key);
            Objects.requireNonNull(name);
            Objects.requireNonNull(type);
            Objects.requireNonNull(codec);
        }

        private void encode(ByteBuf output, Packet value) {
            codec.encode(output, type.cast(value));
        }
    }

    private final Map<Key, Entry<?>> entries;

    public PacketRegistry(Iterable<Entry<?>> entries) {
        Map<Key, Entry<?>> map = new HashMap<>();
        for (Entry<?> entry : entries) {
            if (map.putIfAbsent(entry.key(), entry) != null)
                throw new IllegalArgumentException("Duplicate packet key: " + entry.key());
        }
        this.entries = Map.copyOf(map);
    }

    public Map<Key, Entry<?>> entries() {
        return entries;
    }

    public Packet decode(State state, Flow flow, ByteBuf body) {
        int start = body.readerIndex();
        try {
            int id = WireCodecs.VAR_INT.decode(body);
            Entry<?> entry = require(new Key(state, flow, id));
            Packet packet = entry.codec().decode(body);
            if (body.isReadable()) throw new IllegalArgumentException("Trailing bytes for " + entry.name());
            return packet;
        } catch (RuntimeException e) {
            body.readerIndex(start);
            throw e;
        }
    }

    public void encode(Key key, Packet packet, ByteBuf output) {
        Entry<?> entry = require(key);
        if (!entry.type().isInstance(packet)) throw new IllegalArgumentException("Wrong packet type for " + key);
        int start = output.writerIndex();
        try {
            WireCodecs.VAR_INT.encode(output, key.id());
            entry.encode(output, packet);
        } catch (RuntimeException e) {
            output.writerIndex(start);
            throw e;
        }
    }

    private Entry<?> require(Key key) {
        Entry<?> entry = entries.get(key);
        if (entry == null) throw new IllegalArgumentException("No generated codec for " + key);
        return entry;
    }
}

package org.stackanvil.packetforge.commons.status;

import java.util.*;
import net.kyori.adventure.text.Component;

/** The public server-list response, including an Adventure description. */
public record ServerStatus(
        Component description,
        Optional<Players> players,
        Optional<Version> version,
        Optional<Favicon> favicon,
        boolean enforcesSecureChat) {
    public ServerStatus {
        Objects.requireNonNull(description);
        Objects.requireNonNull(players);
        Objects.requireNonNull(version);
        Objects.requireNonNull(favicon);
    }

    public ServerStatus(Component description) {
        this(description, Optional.empty(), Optional.empty(), Optional.empty(), false);
    }

    public record Players(int max, int online, List<Player> sample) {
        public Players {
            sample = List.copyOf(sample);
        }

        public Players(int max, int online) {
            this(max, online, List.of());
        }
    }

    public record Player(UUID id, String name) {
        public Player {
            Objects.requireNonNull(id);
            Objects.requireNonNull(name);
        }
    }

    public record Version(String name, int protocol) {
        public Version {
            Objects.requireNonNull(name);
        }
    }

    public record Favicon(byte[] iconBytes) {
        public Favicon {
            iconBytes = iconBytes.clone();
        }

        @Override
        public byte[] iconBytes() {
            return iconBytes.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Favicon icon && Arrays.equals(iconBytes, icon.iconBytes);
        }

        @Override
        public int hashCode() {
            return Arrays.hashCode(iconBytes);
        }
    }
}

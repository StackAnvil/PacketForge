/*
 * Adapted from MCProtocolLib, commit 3fbc5a07a364c2f6882d7f2d1e0b400ee2770c0d.
 * Copyright (C) 2013-2021 Steveice10
 * Copyright (C) 2021-2026 GeyserMC
 * Licensed under the MIT License. See META-INF/licenses/mcprotocollib.txt.
 */
package org.stackanvil.packetforge.commons.text;

import java.util.Objects;
import net.kyori.adventure.text.event.DataComponentValue;
import org.stackanvil.packetforge.commons.NbtTag;

/**
 * Holds the raw value of a data component, serialized to NBT.
 *
 * @param tag the value of a data component
 */
public record NbtDataComponentValue(NbtTag tag) implements DataComponentValue {
    public NbtDataComponentValue {
        Objects.requireNonNull(tag);
    }
}

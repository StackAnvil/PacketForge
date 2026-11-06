/*
 * Adapted from MCProtocolLib, commit 3fbc5a07a364c2f6882d7f2d1e0b400ee2770c0d.
 * Copyright (C) 2013-2021 Steveice10
 * Copyright (C) 2021-2026 GeyserMC
 * Licensed under the MIT License. See META-INF/licenses/mcprotocollib.txt.
 */
package org.stackanvil.packetforge.commons.text;

import java.util.Optional;
import net.kyori.adventure.dialog.DialogLike;
import net.kyori.adventure.key.Key;
import org.stackanvil.packetforge.commons.NbtTag;

/**
 * Represents a dialog. This can hold either a {@link Key} reference to a dialog loaded in the {@code "minecraft:dialog"} registry, or an
 * inline-defined dialog serialized as {@link NbtTag.Compound}. Instances of this record will always hold one of the two, never both, and never none.
 *
 * @param reference a reference to a dialog loaded in the {@code "minecraft:dialog"} registry
 * @param inline an inline-defined dialog, serialized as {@link NbtTag.Compound}
 * @see NbtComponentSerializer
 */
public record NbtDialog(Optional<Key> reference, Optional<NbtTag.Compound> inline) implements DialogLike {

    public NbtDialog {
        if (reference.isPresent() == inline.isPresent()) {
            throw new IllegalArgumentException(
                    "NbtDialog must either have a reference ID or an inline NbtTag.Compound, but not both or neither");
        }
    }

    public NbtDialog(Key reference) {
        this(Optional.of(reference), Optional.empty());
    }

    public NbtDialog(NbtTag.Compound inline) {
        this(Optional.empty(), Optional.of(inline));
    }
}

/*
 * Adapted from MCProtocolLib, commit 3fbc5a07a364c2f6882d7f2d1e0b400ee2770c0d.
 * Copyright (C) 2013-2021 Steveice10
 * Copyright (C) 2021-2026 GeyserMC
 * Licensed under the MIT License. See META-INF/licenses/mcprotocollib.txt.
 */
package org.stackanvil.packetforge.commons.text;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.ComponentSerializer;

/** Internal serialization of Adventure components to Java network NBT values. */
interface NbtComponentSerializer extends ComponentSerializer<Component, Component, Object> {

    static NbtComponentSerializer nbt() {
        return NbtComponentSerializerImpl.INSTANCE;
    }
}

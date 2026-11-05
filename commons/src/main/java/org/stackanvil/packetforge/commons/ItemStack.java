package org.stackanvil.packetforge.commons;

import java.util.Objects;

/** Item count, item registry ID, and explicit component changes. */
public record ItemStack(int count, int item, ComponentPatch components) {
    public static final ItemStack EMPTY = new ItemStack(0, 0, ComponentPatch.EMPTY);

    public ItemStack {
        Objects.requireNonNull(components);
        if (count < 0 || item < 0)
            throw new IllegalArgumentException("Negative item count or registry ID");
        if (count == 0 && (item != 0 || !components.equals(ComponentPatch.EMPTY)))
            throw new IllegalArgumentException("Empty stack has item data");
    }
}

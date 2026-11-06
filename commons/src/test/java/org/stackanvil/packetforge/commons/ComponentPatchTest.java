package org.stackanvil.packetforge.commons;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Set;
import net.kyori.adventure.key.Key;
import org.junit.jupiter.api.Test;

class ComponentPatchTest {
    private static final DataComponentType<Integer> NUMBER =
            new DataComponentType<>(0, Key.key("test:number"), WireCodecs.VAR_INT);
    private static final DataComponentType<String> TEXT =
            new DataComponentType<>(1, Key.key("test:text"), WireCodecs.STRING);

    @Test
    void buildsTypedValuesAndTakesImmutableSnapshots() {
        var builder = ComponentPatch.builder().set(NUMBER, 12).set(TEXT, "first");
        var first = builder.build();
        var second = builder.set(NUMBER, 20).remove(TEXT).build();

        assertEquals(12, first.get(NUMBER).orElseThrow());
        assertEquals("first", first.get(TEXT).orElseThrow());
        assertEquals(20, second.get(NUMBER).orElseThrow());
        assertTrue(second.get(TEXT).isEmpty());
        assertTrue(second.isRemoved(TEXT));
        assertEquals("fallback", second.getOrDefault(TEXT, "fallback"));
        assertThrows(UnsupportedOperationException.class, () -> first.added().clear());
        assertThrows(UnsupportedOperationException.class, () -> second.removed().clear());
        assertEquals(ComponentPatch.of(NUMBER.withValue(12), TEXT.withValue("first")), first);
        assertEquals(first.hashCode(), first.toBuilder().build().hashCode());
    }

    @Test
    void distinguishesResetFromRemovalAndAllowsReplacingEitherChange() {
        var removed = ComponentPatch.builder().remove(NUMBER).build();
        assertTrue(removed.isRemoved(NUMBER));
        var reset = removed.toBuilder().reset(NUMBER).build();
        assertEquals(ComponentPatch.EMPTY, reset);
        var replaced = removed.toBuilder().set(NUMBER, 5).build();
        assertFalse(replaced.isRemoved(NUMBER));
        assertEquals(5, replaced.get(NUMBER).orElseThrow());
        assertEquals(ComponentPatch.EMPTY, replaced.toBuilder().reset(NUMBER).build());
        assertTrue(replaced.toBuilder().remove(NUMBER).build().isRemoved(NUMBER));
    }

    @Test
    void rejectsDuplicateAndConflictingIdsInsteadOfCastingTheWrongValue() {
        assertThrows(IllegalArgumentException.class, () -> ComponentPatch.of(NUMBER.withValue(1), NUMBER.withValue(2)));
        assertThrows(
                IllegalArgumentException.class, () -> new ComponentPatch(List.of(NUMBER.withValue(1)), Set.of(NUMBER)));
        var other = new DataComponentType<>(0, Key.key("test:other"), WireCodecs.STRING);
        var patch = ComponentPatch.of(NUMBER.withValue(1));
        assertThrows(IllegalArgumentException.class, () -> patch.get(other));
        assertThrows(IllegalArgumentException.class, () -> patch.toBuilder().set(other, "wrong"));
        assertThrows(
                IllegalArgumentException.class,
                () -> ComponentPatch.builder().remove(NUMBER).set(other, "wrong"));
        assertThrows(IllegalArgumentException.class, () -> new ComponentPatch(List.of(), Set.of(NUMBER, other)));
        assertThrows(
                IllegalArgumentException.class,
                () -> ComponentPatch.builder().remove(NUMBER).build().isRemoved(other));
    }

    @Test
    void rejectsNullValuesAndInvalidKeyIdentities() {
        assertThrows(NullPointerException.class, () -> TEXT.withValue(null));
        assertThrows(NullPointerException.class, () -> ComponentPatch.builder().set(TEXT, null));
        assertThrows(NullPointerException.class, () -> new DataComponentType<>(0, null, WireCodecs.STRING));
        assertThrows(
                IllegalArgumentException.class,
                () -> new DataComponentType<>(-1, Key.key("test:negative"), WireCodecs.STRING));
    }
}

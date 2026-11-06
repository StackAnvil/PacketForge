package org.stackanvil.packetforge.commons;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Immutable added values and explicit removals, addressed by typed component keys. */
public final class ComponentPatch {
    public static final ComponentPatch EMPTY = new ComponentPatch(List.of(), Set.of());

    private final Map<Integer, DataComponent<?>> added;
    private final Set<DataComponentType<?>> removed;

    public ComponentPatch(Collection<? extends DataComponent<?>> added, Set<DataComponentType<?>> removed) {
        var values = new LinkedHashMap<Integer, DataComponent<?>>();
        for (var component : added) {
            if (values.putIfAbsent(component.type().id(), component) != null)
                throw new IllegalArgumentException("Duplicate added component");
        }
        var removals = new LinkedHashMap<Integer, DataComponentType<?>>();
        for (var type : removed) {
            if (removals.putIfAbsent(type.id(), type) != null)
                throw new IllegalArgumentException("Duplicate removed component ID");
            if (values.containsKey(type.id()))
                throw new IllegalArgumentException("Component is both added and removed");
        }
        this.added = Collections.unmodifiableMap(values);
        this.removed = Collections.unmodifiableSet(new LinkedHashSet<>(removals.values()));
    }

    public static ComponentPatch of(DataComponent<?>... components) {
        return new ComponentPatch(List.of(components), Set.of());
    }

    public Collection<DataComponent<?>> added() {
        return added.values();
    }

    public Set<DataComponentType<?>> removed() {
        return removed;
    }

    public <T> Optional<T> get(DataComponentType<T> type) {
        Objects.requireNonNull(type);
        var component = added.get(type.id());
        if (component == null) return Optional.empty();
        requireSameType(type, component.type());
        // The constructor and builder keep each value paired with its own typed key.
        @SuppressWarnings("unchecked")
        T value = (T) component.value();
        return Optional.of(value);
    }

    public <T> T getOrDefault(DataComponentType<T> type, T fallback) {
        return get(type).orElse(fallback);
    }

    public boolean isRemoved(DataComponentType<?> type) {
        Objects.requireNonNull(type);
        for (var removal : removed)
            if (removal.id() == type.id()) {
                requireSameType(type, removal);
                return true;
            }
        return false;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        var builder = new Builder();
        builder.added.putAll(added);
        removed.forEach(type -> builder.removed.put(type.id(), type));
        return builder;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof ComponentPatch that && added.equals(that.added) && removed.equals(that.removed);
    }

    @Override
    public int hashCode() {
        return Objects.hash(added, removed);
    }

    @Override
    public String toString() {
        return "ComponentPatch[added=" + added.values() + ", removed=" + removed + "]";
    }

    private static void requireSameType(DataComponentType<?> expected, DataComponentType<?> actual) {
        if (!expected.equals(actual))
            throw new IllegalArgumentException("Conflicting component key for ID " + expected.id());
    }

    public static final class Builder {
        private final Map<Integer, DataComponent<?>> added = new LinkedHashMap<>();
        private final Map<Integer, DataComponentType<?>> removed = new LinkedHashMap<>();

        private Builder() {}

        public <T> Builder set(DataComponentType<T> type, T value) {
            checkType(type);
            added.put(type.id(), type.withValue(value));
            removed.remove(type.id());
            return this;
        }

        public Builder remove(DataComponentType<?> type) {
            checkType(type);
            added.remove(type.id());
            removed.put(type.id(), type);
            return this;
        }

        /** Remove a change from this patch without requesting removal from the target item. */
        public Builder reset(DataComponentType<?> type) {
            checkType(type);
            added.remove(type.id());
            removed.remove(type.id());
            return this;
        }

        public ComponentPatch build() {
            return new ComponentPatch(added.values(), new LinkedHashSet<>(removed.values()));
        }

        private void checkType(DataComponentType<?> type) {
            Objects.requireNonNull(type);
            var value = added.get(type.id());
            if (value != null) requireSameType(type, value.type());
            var removal = removed.get(type.id());
            if (removal != null) requireSameType(type, removal);
        }
    }
}

package org.stackanvil.packetforge.generator;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.stackanvil.packetforge.generator.Analyzer.Dispatch;
import org.stackanvil.packetforge.generator.Analyzer.Model;
import org.stackanvil.packetforge.generator.Analyzer.Registration;
import org.stackanvil.packetforge.generator.Analyzer.Wire;
import org.stackanvil.packetforge.generator.SourceIndex.Unsupported;

/** Shared typed dependency graph for schema completeness and emission reachability. */
final class SchemaGraph {
    record Selection(List<Model> models, List<Dispatch> dispatches) {}

    private static final Comparator<SchemaReference> ORDER = Comparator.comparingInt(
                    (SchemaReference reference) -> reference instanceof SchemaReference.Dispatch ? 0 : 1)
            .thenComparing(reference -> switch (reference) {
                case SchemaReference.Dispatch dispatch -> dispatch.name();
                case SchemaReference.Model model -> model.javaName();
            });
    private final Map<String, Model> modelsById;
    private final Map<String, List<Model>> modelsByJavaName = new HashMap<>();
    private final Map<String, Dispatch> tablesByName = new HashMap<>();
    private final List<Dispatch> tableOrder;
    private final JavaReferences javaReferences;
    private final Map<SchemaReference, Set<SchemaReference>> edges = new HashMap<>();

    SchemaGraph(Map<String, Model> models, List<Dispatch> dispatches) {
        modelsById = Map.copyOf(models);
        for (var model : models.values()) {
            var candidates = modelsByJavaName.computeIfAbsent(model.generatedName(), ignored -> new ArrayList<>());
            if (!candidates.contains(model)) candidates.add(model);
        }
        for (var dispatch : dispatches) {
            if (tablesByName.putIfAbsent(dispatch.name(), dispatch) != null) {
                throw new Unsupported("Duplicate dispatch table: " + dispatch.name());
            }
        }
        tableOrder = List.copyOf(dispatches);
        javaReferences = new JavaReferences(modelsByJavaName.keySet());
    }

    String missing(Registration registration) {
        try {
            walk(roots(registration));
            return "";
        } catch (Unsupported failure) {
            return failure.getMessage();
        }
    }

    Selection select(List<Registration> registrations) {
        Set<SchemaReference> roots = new LinkedHashSet<>();
        for (var registration : registrations) if (registration.supported()) roots.addAll(roots(registration));
        Set<SchemaReference> selected = walk(roots);
        Map<String, Model> models = new TreeMap<>();
        for (var reference : selected)
            if (reference instanceof SchemaReference.Model type) {
                var model = model(type);
                models.put(model.originalName(), model);
            }
        List<Dispatch> dispatches = tableOrder.stream()
                .filter(table -> selected.contains(new SchemaReference.Dispatch(table.name())))
                .toList();
        return new Selection(List.copyOf(models.values()), dispatches);
    }

    Set<SchemaReference> dependencies(SchemaReference reference) {
        return edges.computeIfAbsent(reference, this::collect);
    }

    private Set<SchemaReference> walk(Set<SchemaReference> roots) {
        Set<SchemaReference> visited = new HashSet<>();
        var pending = new ArrayDeque<>(roots);
        while (!pending.isEmpty()) {
            var reference = pending.removeFirst();
            if (!visited.add(reference)) continue;
            dependencies(reference).stream().sorted(ORDER).forEach(pending::addLast);
        }
        return Set.copyOf(visited);
    }

    private Set<SchemaReference> roots(Registration registration) {
        Set<SchemaReference> roots = new LinkedHashSet<>();
        roots.add(new SchemaReference.Model(registration.generatedClass()));
        if (registration.contextRule() != null) {
            addContextRoot(roots, registration.contextRule().allowedModel());
            addContextRoot(roots, registration.contextRule().fallbackModel());
        }
        return roots;
    }

    private void addContextRoot(Set<SchemaReference> roots, String id) {
        if (id.isEmpty()) return;
        Model model = modelsById.get(id);
        if (model == null) throw new Unsupported("Unresolved context codec model: " + id);
        roots.add(new SchemaReference.Model(model.generatedName()));
    }

    private Set<SchemaReference> collect(SchemaReference reference) {
        Set<SchemaReference> references = new HashSet<>();
        switch (reference) {
            case SchemaReference.Model type -> {
                Model model = model(type);
                for (var field : model.fields()) {
                    add(references, field.read());
                    add(references, field.write());
                }
                if (model.program() != null) {
                    references.addAll(javaReferences.statements(model.program().decode()));
                    references.addAll(javaReferences.statements(model.program().encode()));
                    for (String helper : model.program().helpers()) references.addAll(javaReferences.member(helper));
                }
                for (String validation : model.validations()) references.addAll(javaReferences.statements(validation));
            }
            case SchemaReference.Dispatch referenceTable -> {
                Dispatch table = tablesByName.get(referenceTable.name());
                if (table == null) throw new Unsupported("Unresolved recursive codec table: " + referenceTable.name());
                add(references, table.key());
                add(references, table.implementation());
                for (var branch : table.cases()) add(references, branch.value());
                if (table.fallback() != null) add(references, table.fallback());
            }
        }
        return Set.copyOf(references);
    }

    private Model model(SchemaReference.Model reference) {
        List<Model> candidates = modelsByJavaName.get(reference.javaName());
        if (candidates == null) throw new Unsupported("Unresolved generated model: " + reference.javaName());
        if (candidates.size() != 1) throw new Unsupported("Conflicting generated models: " + reference.javaName());
        return candidates.getFirst();
    }

    private void add(Set<SchemaReference> references, Wire wire) {
        if (ComponentReferences.uses(wire.shape())) {
            var component = tableOrder.stream()
                    .filter(table -> table.registry().equals("DATA_COMPONENT_TYPE"))
                    .findFirst()
                    .orElseThrow(() -> new Unsupported("Unresolved data-component registry types"));
            references.add(new SchemaReference.Dispatch(component.name()));
        }
        references.addAll(javaReferences.type(wire.type()));
        references.addAll(javaReferences.expression(wire.codec()));
    }
}

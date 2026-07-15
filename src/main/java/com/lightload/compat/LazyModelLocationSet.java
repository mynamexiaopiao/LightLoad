package com.lightload.compat;

import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

import java.util.AbstractSet;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

public final class LazyModelLocationSet extends AbstractSet<ResourceLocation> {
    private final Set<ResourceLocation> locations;
    private final Map<String, Set<ResourceLocation>> byNamespace = new LinkedHashMap<>();
    private final Set<String> allNamespaces = new LinkedHashSet<>();
    private final Set<String> generatedNamespaces = new LinkedHashSet<>();

    public LazyModelLocationSet(Set<ResourceLocation> initialLocations) {
        locations = new LinkedHashSet<>(initialLocations);
        for (ResourceLocation location : initialLocations) remember(location);
        BuiltInRegistries.BLOCK.keySet().forEach(key -> allNamespaces.add(key.getNamespace()));
        BuiltInRegistries.ITEM.keySet().forEach(key -> allNamespaces.add(key.getNamespace()));
    }

    public synchronized Set<ResourceLocation> select(Predicate<ResourceLocation> predicate) {
        Set<String> visibleNamespaces = new LinkedHashSet<>();
        for (String namespace : allNamespaces) {
            if (predicate.test(ResourceLocation.fromNamespaceAndPath(namespace, "lightload_namespace_probe"))) {
                visibleNamespaces.add(namespace);
            }
        }
        return selectNamespaces(visibleNamespaces);
    }

    public synchronized Set<ResourceLocation> selectNamespaces(Set<String> namespaces) {
        generate(namespaces);
        Set<ResourceLocation> result = new LinkedHashSet<>();
        for (String namespace : namespaces) {
            Set<ResourceLocation> values = byNamespace.get(namespace);
            if (values != null) result.addAll(values);
        }
        return Collections.unmodifiableSet(result);
    }

    @Override
    public synchronized Iterator<ResourceLocation> iterator() {
        generate(allNamespaces);
        return Collections.unmodifiableSet(locations).iterator();
    }

    @Override
    public synchronized int size() {
        generate(allNamespaces);
        return locations.size();
    }

    @Override
    public synchronized boolean contains(Object value) {
        if (!(value instanceof ResourceLocation location)) return false;
        generate(Set.of(location.getNamespace()));
        return locations.contains(location);
    }

    private void generate(Set<String> requestedNamespaces) {
        Set<String> missing = new LinkedHashSet<>(requestedNamespaces);
        missing.removeAll(generatedNamespaces);
        if (missing.isEmpty()) return;
        BuiltInRegistries.BLOCK.entrySet().forEach(entry -> {
            ResourceLocation blockId = entry.getKey().location();
            if (!missing.contains(blockId.getNamespace())) return;
            entry.getValue().getStateDefinition().getPossibleStates().forEach(state ->
                    addGenerated(BlockModelShaper.stateToModelLocation(state)));
        });
        BuiltInRegistries.ITEM.keySet().forEach(key -> {
            ResourceLocation itemId = key;
            if (missing.contains(itemId.getNamespace())) {
                addGenerated(ModelResourceLocation.inventory(itemId));
            }
        });
        generatedNamespaces.addAll(missing);
    }

    private void addGenerated(ResourceLocation location) {
        if (locations.add(location)) remember(location);
    }

    private void addGenerated(ModelResourceLocation location) {
        // ModelResourceLocation became a record in 1.21.1; this index groups models
        // by their underlying resource id, so the variant is not part of its key.
        addGenerated(location.id());
    }

    private void remember(ResourceLocation location) {
        allNamespaces.add(location.getNamespace());
        byNamespace.computeIfAbsent(location.getNamespace(), ignored -> new LinkedHashSet<>()).add(location);
    }
}

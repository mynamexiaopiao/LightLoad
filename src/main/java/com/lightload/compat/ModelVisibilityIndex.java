package com.lightload.compat;

import net.minecraft.resources.ResourceLocation;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

public final class ModelVisibilityIndex {
    private static final Set<String> NAMESPACE_LOCAL_SCANNERS = Set.of(
            "mekanism", "elevatorid", "dyenamicsandfriends", "cfm", "twilightforest", "aether",
            "industrialforegoing");

    private ModelVisibilityIndex() {
    }

    public static Map<String, Object> restrictKnownScanners(Map<String, Object> configuration) {
        Object restricted = configuration.get("refinedstorage");
        if (restricted == null) return configuration;
        Map<String, Object> expanded = new LinkedHashMap<>(configuration);
        for (String modId : NAMESPACE_LOCAL_SCANNERS) expanded.putIfAbsent(modId, restricted);
        return Collections.unmodifiableMap(expanded);
    }

    public static Map<String, Set<ResourceLocation>> build(Set<ResourceLocation> locations) {
        Map<String, Set<ResourceLocation>> mutable = new LinkedHashMap<>();
        for (ResourceLocation location : locations) {
            mutable.computeIfAbsent(location.getNamespace(), ignored -> new LinkedHashSet<>()).add(location);
        }
        Map<String, Set<ResourceLocation>> result = new LinkedHashMap<>(mutable.size());
        mutable.forEach((namespace, values) -> result.put(namespace, Collections.unmodifiableSet(values)));
        return Collections.unmodifiableMap(result);
    }

    public static Set<ResourceLocation> select(Map<String, Set<ResourceLocation>> index,
                                               Predicate<ResourceLocation> predicate) {
        Set<ResourceLocation> result = new LinkedHashSet<>();
        for (Set<ResourceLocation> locations : index.values()) {
            if (!locations.isEmpty() && predicate.test(locations.iterator().next())) result.addAll(locations);
        }
        return Collections.unmodifiableSet(result);
    }
}

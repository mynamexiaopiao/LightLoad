package com.lightload.core;

public final class ForgeRegistryExistenceCache {
    private static long hits;
    private static long misses;
    private static int entries;
    private static volatile Boolean enabled;

    private ForgeRegistryExistenceCache() {
    }

    public static void recordHit() {
        hits++;
    }

    public static void recordMiss(boolean exists) {
        misses++;
        if (exists) entries++;
    }

    public static boolean enabled() {
        Boolean cached = enabled;
        if (cached != null) return cached;
        boolean computed = StartupCoordinator.aggressiveOptimizationsEnabled()
                && StartupCoordinator.config().forgeRegistryLookupCache();
        enabled = computed;
        return computed;
    }

    public static long hits() {
        return hits;
    }

    public static long misses() {
        return misses;
    }

    public static int entries() {
        return entries;
    }
}

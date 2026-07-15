package com.lightload.core;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.concurrent.atomic.LongAdder;

public final class LazyBlockStateCache {
    private static final LongAdder DEFERRED = new LongAdder();
    private static final LongAdder GENERATED = new LongAdder();
    private static volatile Boolean enabled;

    private LazyBlockStateCache() {
    }

    public static boolean enabled() {
        Boolean cached = enabled;
        if (cached != null) return cached;
        boolean computed = StartupCoordinator.aggressiveOptimizationsEnabled()
                && StartupCoordinator.config().lazyBlockStateCache()
                && !CompatibilityManager.modernFixPresent();
        enabled = computed;
        return computed;
    }

    public static void invalidate(Iterable<Block> blocks) {
        long deferred = 0;
        synchronized (net.minecraft.world.level.block.state.BlockBehaviour.BlockStateBase.class) {
            for (Block block : blocks) {
                for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                    if (state instanceof BlockStateCacheAccess access) {
                        access.lightload$invalidateCache();
                        deferred++;
                    } else {
                        state.initCache();
                    }
                }
            }
        }
        DEFERRED.add(deferred);
    }

    public static void cacheGenerated() {
        GENERATED.increment();
    }

    public static long deferredCaches() {
        return DEFERRED.sum();
    }

    public static long generatedCaches() {
        return GENERATED.sum();
    }
}

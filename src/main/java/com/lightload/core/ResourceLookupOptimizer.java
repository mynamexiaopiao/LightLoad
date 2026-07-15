package com.lightload.core;

import com.lightload.LightloadMod;
import com.lightload.config.LightloadConfig;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.FilePackResources;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraftforge.resource.PathPackResources;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;

/** Ordered parallel lookup for immutable vanilla and Forge mod resource packs. */
public final class ResourceLookupOptimizer {
    private static final AtomicInteger THREAD_ID = new AtomicInteger();
    private static final LongAdder PARALLEL_LOOKUPS = new LongAdder();
    private static volatile ExecutorService executor;

    private ResourceLookupOptimizer() {
    }

    public static boolean enabled() {
        if (!StartupCoordinator.optimizationsActive()) return false;
        LightloadConfig config = StartupCoordinator.config();
        return config.parallelResourceLookup() && !CompatibilityManager.connectorPresent()
                && !CompatibilityManager.lightspeedPresent();
    }

    public static LookupResult findFirst(List<PackResources> packs, PackType type, ResourceLocation location) {
        if (packs == null || packs.isEmpty()) return null;
        int threshold = StartupCoordinator.config().parallelLookupMinPacks();
        if (!enabled() || packs.size() < threshold || packs.stream().anyMatch(pack -> !isSafe(pack))) {
            return sequential(packs, type, location);
        }

        List<Supplier<IoSupplier<InputStream>>> lookups = new ArrayList<>(packs.size());
        for (PackResources pack : packs) {
            lookups.add(() -> pack.getResource(type, location));
        }
        OrderedResult<IoSupplier<InputStream>> result = findFirstOrdered(lookups, threshold, executor());
        if (result == null) return null;
        PARALLEL_LOOKUPS.increment();
        return new LookupResult(result.index(), result.value());
    }

    public static long parallelLookups() {
        return PARALLEL_LOOKUPS.sum();
    }

    static <T> OrderedResult<T> findFirstOrdered(List<? extends Supplier<T>> lookups,
                                                  int threshold, Executor executor) {
        if (lookups.isEmpty()) return null;
        if (lookups.size() < threshold) return sequentialOrdered(lookups);

        List<CompletableFuture<T>> futures = new ArrayList<>(lookups.size());
        try {
            for (Supplier<T> lookup : lookups) {
                futures.add(CompletableFuture.supplyAsync(lookup, executor));
            }
            for (int index = 0; index < futures.size(); index++) {
                T value = futures.get(index).join();
                if (value != null) return new OrderedResult<>(index, value);
            }
            return null;
        } catch (RuntimeException exception) {
            futures.forEach(future -> future.cancel(false));
            return sequentialOrdered(lookups);
        }
    }

    private static LookupResult sequential(List<PackResources> packs, PackType type, ResourceLocation location) {
        for (int index = 0; index < packs.size(); index++) {
            IoSupplier<InputStream> resource = packs.get(index).getResource(type, location);
            if (resource != null) return new LookupResult(index, resource);
        }
        return null;
    }

    private static <T> OrderedResult<T> sequentialOrdered(List<? extends Supplier<T>> lookups) {
        for (int index = 0; index < lookups.size(); index++) {
            T value = lookups.get(index).get();
            if (value != null) return new OrderedResult<>(index, value);
        }
        return null;
    }

    public static boolean isSafe(PackResources pack) {
        Class<?> type = pack.getClass();
        boolean forgePathPack = pack instanceof PathPackResources
                && (type == PathPackResources.class
                || type.getName().startsWith("net.minecraftforge.resource.ResourcePackLoader$"));
        return (forgePathPack || type == FilePackResources.class) && !FusionPackCompat.hasOverrides(pack);
    }

    private static ExecutorService executor() {
        ExecutorService current = executor;
        if (current != null) return current;
        synchronized (ResourceLookupOptimizer.class) {
            current = executor;
            if (current == null) {
                int workers = Math.max(2, Math.min(Runtime.getRuntime().availableProcessors(), 16));
                current = Executors.newFixedThreadPool(workers, runnable -> {
                    Thread thread = new Thread(runnable, "Lightload-Resource-" + THREAD_ID.incrementAndGet());
                    thread.setDaemon(true);
                    thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
                    return thread;
                });
                executor = current;
                LightloadMod.LOGGER.debug("[Lightload] Resource lookup executor started with {} workers", workers);
            }
            return current;
        }
    }

    public record LookupResult(int index, IoSupplier<InputStream> resource) {
    }

    record OrderedResult<T>(int index, T value) {
    }
}

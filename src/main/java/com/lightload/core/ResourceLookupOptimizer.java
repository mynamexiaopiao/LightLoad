package com.lightload.core;

import com.lightload.LightloadMod;
import com.lightload.config.LightloadConfig;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.FilePackResources;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.server.packs.PathPackResources;

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
import java.util.function.Predicate;

/** Ordered parallel lookup for immutable vanilla and NeoForge mod resource packs. */
public final class ResourceLookupOptimizer {
    private static final int LOOKUP_BATCH_SIZE = 8;
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

    /** Looks up a segment already classified as safe by FallbackResourceManagerMixin. */
    public static LookupResult findFirstSafe(List<PackResources> packs, PackType type,
                                             ResourceLocation location) {
        if (packs == null || packs.isEmpty()) return null;
        int threshold = StartupCoordinator.config().parallelLookupMinPacks();
        if (!enabled() || packs.size() < threshold) return sequential(packs, type, location);
        List<Supplier<IoSupplier<InputStream>>> lookups = new ArrayList<>(packs.size());
        for (PackResources pack : packs) lookups.add(() -> pack.getResource(type, location));
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

        for (int start = 0; start < lookups.size(); start += LOOKUP_BATCH_SIZE) {
            int end = Math.min(lookups.size(), start + LOOKUP_BATCH_SIZE);
            List<CompletableFuture<T>> futures = new ArrayList<>(end - start);
            try {
                for (int index = start; index < end; index++) {
                    futures.add(CompletableFuture.supplyAsync(lookups.get(index), executor));
                }
                for (int offset = 0; offset < futures.size(); offset++) {
                    T value = futures.get(offset).join();
                    if (value != null) {
                        futures.forEach(future -> future.cancel(true));
                        return new OrderedResult<>(start + offset, value);
                    }
                }
            } catch (RuntimeException exception) {
                futures.forEach(future -> future.cancel(true));
                return sequentialOrdered(lookups);
            }
        }
        return null;
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
                || type.getName().startsWith("net.neoforged.neoforge.resource.ResourcePackLoader$"));
        return (forgePathPack || type == FilePackResources.class) && !FusionPackCompat.hasOverrides(pack);
    }

    private static ExecutorService executor() {
        ExecutorService current = executor;
        if (current != null) return current;
        synchronized (ResourceLookupOptimizer.class) {
            current = executor;
            if (current == null) {
                int workers = Math.max(2, Math.min(Math.max(1,
                        Runtime.getRuntime().availableProcessors() / 2), 8));
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

    /** Lookup plan data lives outside the mixin package so transformed game code can load it safely. */
    public sealed interface LookupStep permits SafeSegment, Barrier {
    }

    public record IndexedPack(int index, PackResources pack) {
    }

    public record SafeSegment(List<IndexedPack> indexed, List<PackResources> packs) implements LookupStep {
    }

    public record Barrier(int index, PackResources pack, String name,
                          Predicate<ResourceLocation> filter) implements LookupStep {
        public boolean isFiltered(ResourceLocation location) {
            return filter != null && filter.test(location);
        }
    }

    record OrderedResult<T>(int index, T value) {
    }
}

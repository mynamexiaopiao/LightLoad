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
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Ordered parallel lookup for immutable vanilla and Forge mod resource packs. */
public final class ResourceLookupOptimizer {
    private static final int MAX_LOOKUP_WORKERS = 8;
    private static final int LOOKUP_WAVE_SIZE = 8;
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
        if (CompatibilityManager.modernFixPresent()) threshold = Math.max(threshold, 16);
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

        List<CompletableFuture<T>> activeWave = List.of();
        try {
            for (int base = 0; base < lookups.size(); base += LOOKUP_WAVE_SIZE) {
                int end = Math.min(lookups.size(), base + LOOKUP_WAVE_SIZE);
                List<CompletableFuture<T>> wave = new ArrayList<>(end - base);
                activeWave = wave;
                for (int index = base; index < end; index++) {
                    wave.add(CompletableFuture.supplyAsync(lookups.get(index), executor));
                }
                for (int offset = 0; offset < wave.size(); offset++) {
                    T value = wave.get(offset).join();
                    if (value != null) {
                        cancel(wave, offset + 1);
                        return new OrderedResult<>(base + offset, value);
                    }
                }
            }
            return null;
        } catch (RuntimeException exception) {
            cancel(activeWave, 0);
            return sequentialOrdered(lookups);
        }
    }

    private static void cancel(List<? extends CompletableFuture<?>> futures, int fromIndex) {
        for (int index = fromIndex; index < futures.size(); index++) futures.get(index).cancel(false);
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
                int workers = Math.max(2, Math.min(
                        Math.max(2, Runtime.getRuntime().availableProcessors() / 2), MAX_LOOKUP_WORKERS));
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

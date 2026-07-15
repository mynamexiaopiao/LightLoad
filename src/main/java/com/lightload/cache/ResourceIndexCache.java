package com.lightload.cache;

import com.lightload.LightloadMod;
import com.lightload.config.LightloadConfig;
import com.lightload.core.CompatibilityManager;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraftforge.fml.loading.FMLLoader;
import net.minecraftforge.forgespi.locating.IModFile;
import net.minecraftforge.resource.PathPackResources;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** The hot-path cache: persisted resource existence results, isolated per mod file. */
public final class ResourceIndexCache {
    public static final int UNKNOWN_ROUTE = Integer.MIN_VALUE;
    public static final int MISSING_ROUTE = -1;

    private final LightloadConfig config;
    private final boolean effectiveExistenceCache;
    private final boolean effectiveContentCache;
    private final boolean effectiveRouteCache;
    private final boolean effectivePathIndex;
    private final ResourceIndexShardStore store;
    private final Path contentFile;
    private final Path routeFile;
    private final CompletableFuture<Map<String, ResourceContentStore.PackContent>> persistedContents;
    private final CompletableFuture<Map<String, Map<String, Integer>>> persistedRoutes;
    private final CompletableFuture<Map<String, ResourcePathStore.PersistedPaths>> persistedPaths;
    private final ConcurrentHashMap<PathPackResources, Optional<RuntimePack>> runtimePacks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<PathPackResources, PackBinding> bindings = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, Integer>> routeTables = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> vanillaClient = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> vanillaServer = new ConcurrentHashMap<>();
    private final Set<CompletableFuture<Void>> pendingPathScans = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean vanillaClientDirty = new AtomicBoolean();
    private final AtomicBoolean vanillaServerDirty = new AtomicBoolean();
    private final AtomicBoolean contentDirty = new AtomicBoolean();
    private final AtomicBoolean routesDirty = new AtomicBoolean();
    private final PackFingerprint vanillaClientFingerprint;
    private final PackFingerprint vanillaServerFingerprint;
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong loadedPacks = new AtomicLong();
    private final AtomicLong pathPacksLoaded = new AtomicLong();
    private final AtomicLong persistedPacks = new AtomicLong();
    private final AtomicLong contentHits = new AtomicLong();
    private final AtomicLong contentMisses = new AtomicLong();
    private final AtomicLong contentCaptures = new AtomicLong();
    private final AtomicLong contentBytesServed = new AtomicLong();
    private final AtomicLong retainedContentBytes = new AtomicLong();
    private final AtomicLong routeHits = new AtomicLong();
    private final AtomicLong routeMisses = new AtomicLong();
    private final AtomicLong routeRepairs = new AtomicLong();
    private final AtomicLong routeLayouts = new AtomicLong();
    private final AtomicLong pathIndexHits = new AtomicLong();
    private final AtomicLong pathIndexMisses = new AtomicLong();
    private final AtomicLong asyncScans = new AtomicLong();
    private final ExecutorService loader = Executors.newFixedThreadPool(
            Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 4)), runnable -> {
        Thread thread = new Thread(runnable, "lightload-cache-loader");
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });
    private final ExecutorService writer = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "lightload-cache-writer");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });

    public ResourceIndexCache(LightloadConfig config, Path cacheDirectory) {
        this.config = config;
        boolean optiFine = CompatibilityManager.optiFinePresent();
        this.effectiveExistenceCache = isUsable() && config.cacheResourceExistence()
                && !CompatibilityManager.lightspeedPresent() && !optiFine;
        this.effectiveContentCache = isUsable() && config.cacheResourceContent()
                && !CompatibilityManager.lightspeedPresent() && !optiFine;
        this.effectiveRouteCache = isUsable() && config.cacheResourceRoutes()
                && !CompatibilityManager.lightspeedPresent() && !optiFine;
        this.effectivePathIndex = isUsable()
                && !CompatibilityManager.resourcePathCompatibilityRiskPresent()
                && !CompatibilityManager.lightspeedPresent() && !optiFine;
        this.store = new ResourceIndexShardStore(cacheDirectory, mcVersion(), forgeVersion());
        this.contentFile = cacheDirectory.resolve("resource-content-v3.bin");
        this.routeFile = cacheDirectory.resolve("resource-routes-v1.bin");
        this.persistedPaths = config.persistentCache() && effectivePathIndex
                ? CompletableFuture.supplyAsync(store::loadAllPaths, loader)
                : CompletableFuture.completedFuture(new ConcurrentHashMap<>());
        this.persistedContents = config.persistentCache() && config.cacheResourceContent()
                ? CompletableFuture.supplyAsync(this::loadContents, loader)
                : CompletableFuture.completedFuture(Map.of());
        this.persistedRoutes = config.persistentCache() && config.cacheResourceRoutes()
                ? CompletableFuture.supplyAsync(this::loadRoutes, loader)
                : CompletableFuture.completedFuture(Map.of());
        this.vanillaClientFingerprint = new PackFingerprint("minecraft-client", mcVersion(), 0, 0);
        this.vanillaServerFingerprint = new PackFingerprint("minecraft-server", mcVersion(), 0, 0);
        loadVanilla("minecraft-client", vanillaClientFingerprint, vanillaClient);
        loadVanilla("minecraft-server", vanillaServerFingerprint, vanillaServer);
    }

    public boolean isUsable() {
        return config.enabled() && config.memoryCache();
    }

    public boolean existenceCacheEnabled() {
        return effectiveExistenceCache;
    }

    public boolean packExistenceCacheEnabled() {
        return existenceCacheEnabled();
    }

    public boolean routeCacheEnabled() {
        return effectiveRouteCache;
    }

    public boolean contentCacheEnabled() {
        return effectiveContentCache;
    }

    public boolean pathIndexEnabled() {
        return effectivePathIndex;
    }

    public void bindModFile(PathPackResources pack, IModFile modFile) {
        Optional<PackFingerprint> fingerprint = PackFingerprint.create(pack.packId(), modFile.getFilePath());
        if (fingerprint.isEmpty()) return;
        String module = modFile.getModFileInfo().moduleName();
        String version = modFile.getModFileInfo().versionString();
        String baseName = fileBaseName(modFile.getFilePath());
        String pathHash = Integer.toUnsignedString(fingerprint.get().source().hashCode(), 16);
        bindings.put(pack, new PackBinding(
                sanitize(module + version + "-" + baseName + "-" + pathHash), fingerprint.get()));
        runtimePacks.remove(pack);
    }

    public void startCachePreload(PathPackResources pack) {
        if ((packExistenceCacheEnabled() || contentCacheEnabled() || pathIndexEnabled())
                && config.persistentCache()) runtimePack(pack);
    }

    public Optional<Set<String>> findNamespaces(PathPackResources pack, PackType type) {
        return findPathGroup(pack, "N|" + type.name()).map(Set::copyOf);
    }

    public void recordNamespaces(PathPackResources pack, PackType type, Set<String> namespaces) {
        recordPathGroup(pack, "N|" + type.name(), namespaces.stream().sorted().toList());
    }

    public Optional<List<String>> findResourcePaths(PathPackResources pack, PackType type, String namespace) {
        return findPathGroup(pack, "P|" + type.name() + "|" + namespace);
    }

    public void recordResourcePaths(PathPackResources pack, PackType type, String namespace, List<String> paths) {
        recordPathGroup(pack, "P|" + type.name() + "|" + namespace, List.copyOf(paths));
    }

    public void scanAsync(Runnable scan) {
        if (!pathIndexEnabled()) return;
        asyncScans.incrementAndGet();
        CompletableFuture<Void> future = CompletableFuture.runAsync(scan, loader);
        pendingPathScans.add(future);
        future.whenComplete((ignored, throwable) -> pendingPathScans.remove(future));
    }

    public Optional<Boolean> findCachedExistence(PathPackResources pack, PackType type,
                                                  ResourceLocation location) {
        return find(pack, type.name() + "|" + location);
    }

    public void recordExistence(PathPackResources pack, PackType type, ResourceLocation location, boolean exists) {
        record(pack, type.name() + "|" + location, exists);
    }

    public Optional<Boolean> findCachedRootExistence(PathPackResources pack, String[] paths) {
        return find(pack, "ROOT|" + Arrays.toString(paths));
    }

    public void recordRootExistence(PathPackResources pack, String[] paths, boolean exists) {
        record(pack, "ROOT|" + Arrays.toString(paths), exists);
    }

    public Optional<IoSupplier<InputStream>> findCachedContent(PathPackResources pack, PackType type,
                                                               ResourceLocation location) {
        if (!contentCacheEnabled() || !cacheable(location)) return Optional.empty();
        RuntimePack runtime = runtimePack(pack);
        if (runtime == null) return Optional.empty();
        ContentSlot slot = runtime.contents.get(contentKey(type, location));
        if (slot == null && runtime.contentLoad.isDone()) {
            awaitContentLoaded(runtime);
            slot = runtime.contents.get(contentKey(type, location));
        }
        byte[] bytes = slot == null ? null : slot.bytes;
        if (bytes == null) {
            return Optional.empty();
        }
        contentHits.incrementAndGet();
        return Optional.of(new CachedContentSupplier(bytes, contentBytesServed));
    }

    public IoSupplier<InputStream> wrapContent(PathPackResources pack, PackType type, ResourceLocation location,
                                               IoSupplier<InputStream> original) {
        if (!contentCacheEnabled() || original == null || original instanceof CachedContentSupplier
                || !cacheable(location)) return original;
        RuntimePack runtime = runtimePack(pack);
        if (runtime == null) return original;
        if (!runtime.contentLoad.isDone()) return original;
        awaitContentLoaded(runtime);
        ContentSlot slot = runtime.contents.computeIfAbsent(contentKey(type, location), ignored -> new ContentSlot());
        byte[] cached = slot.bytes;
        if (cached != null) {
            contentHits.incrementAndGet();
            return new CachedContentSupplier(cached, contentBytesServed);
        }
        if (slot.missCounted.compareAndSet(false, true)) contentMisses.incrementAndGet();
        return () -> {
            byte[] bytes = slot.bytes;
            if (bytes == null) {
                synchronized (slot) {
                    bytes = slot.bytes;
                    if (bytes == null) {
                        try (InputStream input = original.get()) {
                            bytes = input.readAllBytes();
                        }
                        if (bytes.length <= config.maxCachedResourceBytes() && reserveContent(bytes.length)) {
                            slot.bytes = bytes;
                            contentDirty.set(true);
                            contentCaptures.incrementAndGet();
                        }
                    }
                }
            }
            return new ByteArrayInputStream(bytes);
        };
    }

    public int findRoute(String layout, PackType type, ResourceLocation location) {
        if (!routeCacheEnabled()) return UNKNOWN_ROUTE;
        Integer winner = routeTable(layout).get(contentKey(type, location));
        if (winner == null) {
            routeMisses.incrementAndGet();
            return UNKNOWN_ROUTE;
        }
        routeHits.incrementAndGet();
        return winner;
    }

    public void recordRoute(String layout, PackType type, ResourceLocation location, int winner) {
        if (!routeCacheEnabled()) return;
        String key = contentKey(type, location);
        Integer previous = routeTable(layout).put(key, winner);
        if (!java.util.Objects.equals(previous, winner)) routesDirty.set(true);
    }

    public void invalidateRoute(String layout, PackType type, ResourceLocation location) {
        if (!routeCacheEnabled()) return;
        if (routeTable(layout).remove(contentKey(type, location)) != null) {
            routeRepairs.incrementAndGet();
            routesDirty.set(true);
        }
    }

    public void recordRouteLayouts(int count) {
        if (count > 0) routeLayouts.addAndGet(count);
    }

    public Boolean findVanillaExistence(PackType type, ResourceLocation location) {
        if (!existenceCacheEnabled()) return null;
        Boolean result = vanilla(type).get(location.toString());
        if (result == null) misses.incrementAndGet();
        else hits.incrementAndGet();
        return result;
    }

    public void recordVanillaExistence(PackType type, ResourceLocation location, boolean exists) {
        if (!existenceCacheEnabled()) return;
        Map<String, Boolean> target = vanilla(type);
        Boolean previous = target.put(location.toString(), exists);
        if (!java.util.Objects.equals(previous, exists)) vanillaDirty(type).set(true);
    }

    public void persistAsync() {
        if ((!existenceCacheEnabled() && !contentCacheEnabled() && !routeCacheEnabled() && !pathIndexEnabled())
                || !config.persistentCache()) return;
        writer.execute(() -> {
            CompletableFuture<?>[] scans = pendingPathScans.toArray(new CompletableFuture[0]);
            if (scans.length > 0) CompletableFuture.allOf(scans).join();
            Map<String, Snapshot> snapshots = snapshots();
            Map<String, Map<String, Integer>> routeSnapshots = routeSnapshots();
            if (contentCacheEnabled() && contentDirty.get()) persistContents(snapshots);
            if (routeCacheEnabled() && routesDirty.get()) persistRoutes(routeSnapshots);
            if (pathIndexEnabled()) {
                snapshots.forEach((id, snapshot) -> {
                    if (snapshot.pathsDirty()) persistPaths(id, snapshot.fingerprint(), snapshot.paths());
                });
            }
            if (existenceCacheEnabled()) {
                if (packExistenceCacheEnabled()) {
                    snapshots.forEach((id, snapshot) -> {
                        if (snapshot.existenceDirty()) persist(id, snapshot.fingerprint(), snapshot.existences());
                    });
                }
                if (vanillaClientDirty.get()) {
                    persist("minecraft-client", vanillaClientFingerprint, Map.copyOf(vanillaClient));
                }
                if (vanillaServerDirty.get()) {
                    persist("minecraft-server", vanillaServerFingerprint, Map.copyOf(vanillaServer));
                }
            }
        });
    }

    public Stats stats() {
        long entries = vanillaClient.size() + vanillaServer.size();
        for (Optional<RuntimePack> optional : runtimePacks.values()) {
            if (optional.isPresent()) entries += optional.get().existences.size();
        }
        long routeEntries = routeTables.values().stream().mapToLong(Map::size).sum();
        return new Stats(hits.get(), misses.get(), loadedPacks.get(), pathPacksLoaded.get(),
                persistedPacks.get(), entries, store.diskBytes(),
                contentHits.get(), contentMisses.get(), contentCaptures.get(), contentBytesServed.get(),
                retainedContentBytes.get(), fileSize(contentFile), routeHits.get(), routeMisses.get(),
                routeRepairs.get(), routeLayouts.get(), routeEntries, fileSize(routeFile),
                pathIndexHits.get(), pathIndexMisses.get(), asyncScans.get());
    }

    private Optional<List<String>> findPathGroup(PathPackResources pack, String key) {
        if (!pathIndexEnabled()) return Optional.empty();
        RuntimePack runtime = runtimePack(pack);
        if (runtime == null) return Optional.empty();
        List<String> values = runtime.paths.get(key);
        if (values == null && runtime.pathsLoad.isDone()) {
            awaitPathsLoaded(runtime);
            values = runtime.paths.get(key);
        }
        if (values == null) pathIndexMisses.incrementAndGet();
        else pathIndexHits.incrementAndGet();
        return Optional.ofNullable(values);
    }

    private void recordPathGroup(PathPackResources pack, String key, List<String> values) {
        if (!pathIndexEnabled()) return;
        RuntimePack runtime = runtimePack(pack);
        if (runtime == null) return;
        List<String> previous = runtime.paths.put(key, values);
        if (!java.util.Objects.equals(previous, values)) runtime.pathsDirty.set(true);
    }

    private Optional<Boolean> find(PathPackResources pack, String key) {
        if (!packExistenceCacheEnabled()) return Optional.empty();
        RuntimePack runtime = runtimePack(pack);
        if (runtime == null) return Optional.empty();
        Boolean result = runtime.existences.get(key);
        if (result == null && runtime.existencesLoad.isDone()) {
            awaitExistencesLoaded(runtime);
            result = runtime.existences.get(key);
        }
        if (result == null) {
            misses.incrementAndGet();
            return Optional.empty();
        }
        hits.incrementAndGet();
        return Optional.of(result);
    }

    private void record(PathPackResources pack, String key, boolean exists) {
        if (!packExistenceCacheEnabled()) return;
        RuntimePack runtime = runtimePack(pack);
        if (runtime == null) return;
        Boolean previous = runtime.existences.put(key, exists);
        if (!java.util.Objects.equals(previous, exists)) runtime.existenceDirty.set(true);
    }

    private RuntimePack runtimePack(PathPackResources pack) {
        return runtimePacks.computeIfAbsent(pack, this::createRuntimePack).orElse(null);
    }

    private Optional<RuntimePack> createRuntimePack(PathPackResources pack) {
        PackBinding binding = bindings.get(pack);
        Optional<PackFingerprint> fingerprint = binding == null
                ? PackFingerprint.create(pack.packId(), pack.getSource())
                : Optional.of(binding.fingerprint());
        if (fingerprint.isEmpty()) return Optional.empty();
        String id = binding == null
                ? sanitize(pack.packId() + "-" + Integer.toUnsignedString(fingerprint.get().source().hashCode(), 16))
                : binding.cacheId();
        RuntimePack runtime = new RuntimePack(id, fingerprint.get());
        if (config.persistentCache()) {
            runtime.pathsLoad = pathIndexEnabled()
                    ? persistedPaths.thenAccept(paths -> loadPaths(runtime, paths))
                    : CompletableFuture.completedFuture(null);
            runtime.existencesLoad = packExistenceCacheEnabled()
                    ? CompletableFuture.runAsync(() -> loadExistences(runtime), loader)
                    : CompletableFuture.completedFuture(null);
            runtime.contentLoad = contentCacheEnabled()
                    ? persistedContents.thenAccept(contents -> loadContent(runtime, contents))
                    : CompletableFuture.completedFuture(null);
        }
        return Optional.of(runtime);
    }

    private void loadExistences(RuntimePack runtime) {
        store.loadExistences(runtime.cacheId, runtime.fingerprint).ifPresent(existences -> {
            runtime.existences.putAll(existences);
            loadedPacks.incrementAndGet();
        });
    }

    private void loadPaths(RuntimePack runtime, Map<String, ResourcePathStore.PersistedPaths> paths) {
        ResourcePathStore.PersistedPaths persisted = paths.remove(runtime.cacheId);
        if (persisted != null && persisted.fingerprint().equals(runtime.fingerprint)) {
            runtime.paths.putAll(persisted.groups());
            pathPacksLoaded.incrementAndGet();
        }
    }

    private void loadContent(RuntimePack runtime,
                             Map<String, ResourceContentStore.PackContent> contents) {
        ResourceContentStore.PackContent persisted = contents.get(runtime.fingerprint.key());
        if (persisted != null && persisted.fingerprint().equals(runtime.fingerprint)) {
            long bytes = persisted.entries().values().stream().mapToLong(value -> value.length).sum();
            if (reserveContent(bytes)) {
                persisted.entries().forEach((key, value) -> runtime.contents.put(key, new ContentSlot(value)));
            }
        }
    }

    private void loadVanilla(String id, PackFingerprint fingerprint, Map<String, Boolean> target) {
        if (!config.persistentCache() || !config.cacheResourceExistence()) return;
        store.loadExistences(id, fingerprint).ifPresent(existences -> {
            target.putAll(existences);
            loadedPacks.incrementAndGet();
        });
    }

    private void awaitExistencesLoaded(RuntimePack runtime) {
        awaitLoaded(runtime, runtime.existencesLoad, runtime.existencesLoaded, "existence");
    }

    private void awaitPathsLoaded(RuntimePack runtime) {
        awaitLoaded(runtime, runtime.pathsLoad, runtime.pathsLoaded, "path");
    }

    private void awaitContentLoaded(RuntimePack runtime) {
        awaitLoaded(runtime, runtime.contentLoad, runtime.contentLoaded, "content");
    }

    private void awaitLoaded(RuntimePack runtime, CompletableFuture<Void> load,
                             AtomicBoolean loaded, String layer) {
        if (loaded.get()) return;
        synchronized (loaded) {
            if (loaded.get()) return;
            try {
                load.join();
            } catch (RuntimeException exception) {
                LightloadMod.LOGGER.warn("[Lightload] Could not load {} cache {}",
                        layer, runtime.cacheId, exception);
            } finally {
                loaded.set(true);
            }
        }
    }

    private Map<String, Snapshot> snapshots() {
        Map<String, Snapshot> result = new LinkedHashMap<>();
        runtimePacks.values().stream().flatMap(Optional::stream).forEach(runtime -> {
            awaitPathsLoaded(runtime);
            awaitExistencesLoaded(runtime);
            awaitContentLoaded(runtime);
            Map<String, byte[]> contents = new LinkedHashMap<>();
            runtime.contents.forEach((key, slot) -> {
                if (slot.bytes != null) contents.put(key, slot.bytes);
            });
            result.put(runtime.cacheId, new Snapshot(runtime.fingerprint, Map.copyOf(runtime.existences),
                    Map.copyOf(contents), Map.copyOf(runtime.paths), runtime.existenceDirty.get(),
                    runtime.pathsDirty.get()));
        });
        return Map.copyOf(result);
    }

    private ConcurrentHashMap<String, Integer> routeTable(String layout) {
        return routeTables.computeIfAbsent(layout, key -> {
            Map<String, Integer> persisted = persistedRoutes.join().get(key);
            return persisted == null ? new ConcurrentHashMap<>() : new ConcurrentHashMap<>(persisted);
        });
    }

    private Map<String, Map<String, Integer>> routeSnapshots() {
        Map<String, Map<String, Integer>> snapshots = new LinkedHashMap<>();
        routeTables.forEach((layout, routes) -> snapshots.put(layout, Map.copyOf(routes)));
        return Map.copyOf(snapshots);
    }

    private void persist(String id, PackFingerprint fingerprint, Map<String, Boolean> existences) {
        try {
            store.writeExistences(id, fingerprint, existences);
            persistedPacks.incrementAndGet();
        } catch (IOException | RuntimeException exception) {
            LightloadMod.LOGGER.warn("[Lightload] Could not persist resource existence cache {}", id, exception);
        }
    }

    private void persistPaths(String id, PackFingerprint fingerprint, Map<String, List<String>> paths) {
        try {
            store.writePaths(id, fingerprint, paths);
        } catch (IOException | RuntimeException exception) {
            LightloadMod.LOGGER.warn("[Lightload] Could not persist resource path cache {}", id, exception);
        }
    }

    private Map<String, ResourceContentStore.PackContent> loadContents() {
        try {
            return ResourceContentStore.read(contentFile, mcVersion(), forgeVersion());
        } catch (IOException | RuntimeException exception) {
            LightloadMod.LOGGER.info("[Lightload] Ignoring unavailable resource content cache: {}", exception.getMessage());
            return Map.of();
        }
    }

    private Map<String, Map<String, Integer>> loadRoutes() {
        try {
            return ResourceRouteStore.read(routeFile, mcVersion(), forgeVersion());
        } catch (IOException | RuntimeException exception) {
            LightloadMod.LOGGER.info("[Lightload] Ignoring unavailable resource route cache: {}", exception.getMessage());
            return Map.of();
        }
    }

    private void persistContents(Map<String, Snapshot> snapshots) {
        Map<String, ResourceContentStore.PackContent> packs = new LinkedHashMap<>();
        snapshots.values().forEach(snapshot -> {
            if (!snapshot.contents().isEmpty()) {
                packs.put(snapshot.fingerprint().key(),
                        new ResourceContentStore.PackContent(snapshot.fingerprint(), snapshot.contents()));
            }
        });
        try {
            ResourceContentStore.write(contentFile, mcVersion(), forgeVersion(), Map.copyOf(packs));
        } catch (IOException | RuntimeException exception) {
            LightloadMod.LOGGER.warn("[Lightload] Could not persist resource content cache", exception);
        }
    }

    private void persistRoutes(Map<String, Map<String, Integer>> routes) {
        try {
            ResourceRouteStore.write(routeFile, mcVersion(), forgeVersion(), routes);
            routesDirty.set(false);
        } catch (IOException | RuntimeException exception) {
            LightloadMod.LOGGER.warn("[Lightload] Could not persist resource route cache", exception);
        }
    }

    private boolean reserveContent(long bytes) {
        while (true) {
            long current = retainedContentBytes.get();
            if (current + bytes > config.maxContentCacheBytes()) return false;
            if (retainedContentBytes.compareAndSet(current, current + bytes)) return true;
        }
    }

    private Map<String, Boolean> vanilla(PackType type) {
        return type == PackType.CLIENT_RESOURCES ? vanillaClient : vanillaServer;
    }

    private AtomicBoolean vanillaDirty(PackType type) {
        return type == PackType.CLIENT_RESOURCES ? vanillaClientDirty : vanillaServerDirty;
    }

    private static String fileBaseName(Path path) {
        String name = path.getFileName() == null ? "mod" : path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String sanitize(String value) {
        String sanitized = value.replaceAll("[^a-zA-Z0-9.-]", "");
        if (sanitized.isEmpty()) return "pack";
        return sanitized.length() <= 180 ? sanitized : sanitized.substring(0, 180);
    }

    private static String contentKey(PackType type, ResourceLocation location) {
        return type.name() + "|" + location;
    }

    private static boolean cacheable(ResourceLocation location) {
        String path = location.getPath();
        return path.endsWith(".json") || path.endsWith(".mcmeta") || path.endsWith(".lang")
                || path.endsWith(".properties") || path.endsWith(".txt") || path.endsWith(".vsh")
                || path.endsWith(".fsh") || path.endsWith(".glsl");
    }

    private static long fileSize(Path path) {
        try {
            return Files.isRegularFile(path) ? Files.size(path) : 0;
        } catch (IOException ignored) {
            return 0;
        }
    }

    private static String mcVersion() {
        try {
            return FMLLoader.versionInfo() == null ? "unknown" : FMLLoader.versionInfo().mcVersion();
        } catch (RuntimeException ignored) {
            return "unknown";
        }
    }

    private static String forgeVersion() {
        try {
            return FMLLoader.versionInfo() == null ? "unknown" : FMLLoader.versionInfo().forgeVersion();
        } catch (RuntimeException ignored) {
            return "unknown";
        }
    }

    private static final class RuntimePack {
        private final String cacheId;
        private final PackFingerprint fingerprint;
        private final ConcurrentHashMap<String, Boolean> existences = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, ContentSlot> contents = new ConcurrentHashMap<>();
        private final ConcurrentHashMap<String, List<String>> paths = new ConcurrentHashMap<>();
        private final AtomicBoolean existenceDirty = new AtomicBoolean();
        private final AtomicBoolean pathsDirty = new AtomicBoolean();
        private volatile CompletableFuture<Void> existencesLoad = CompletableFuture.completedFuture(null);
        private volatile CompletableFuture<Void> pathsLoad = CompletableFuture.completedFuture(null);
        private volatile CompletableFuture<Void> contentLoad = CompletableFuture.completedFuture(null);
        private final AtomicBoolean existencesLoaded = new AtomicBoolean();
        private final AtomicBoolean pathsLoaded = new AtomicBoolean();
        private final AtomicBoolean contentLoaded = new AtomicBoolean();

        private RuntimePack(String cacheId, PackFingerprint fingerprint) {
            this.cacheId = cacheId;
            this.fingerprint = fingerprint;
        }
    }

    private static final class ContentSlot {
        private volatile byte[] bytes;
        private final AtomicBoolean missCounted = new AtomicBoolean();

        private ContentSlot() {
        }

        private ContentSlot(byte[] bytes) {
            this.bytes = bytes;
        }
    }

    private static final class CachedContentSupplier implements IoSupplier<InputStream> {
        private final byte[] bytes;
        private final AtomicLong served;

        private CachedContentSupplier(byte[] bytes, AtomicLong served) {
            this.bytes = bytes;
            this.served = served;
        }

        @Override
        public InputStream get() {
            served.addAndGet(bytes.length);
            return new ByteArrayInputStream(bytes);
        }
    }

    private record PackBinding(String cacheId, PackFingerprint fingerprint) {
    }

    private record Snapshot(PackFingerprint fingerprint, Map<String, Boolean> existences,
                            Map<String, byte[]> contents, Map<String, List<String>> paths,
                            boolean existenceDirty, boolean pathsDirty) {
    }

    public record Stats(long hits, long misses, long loadedPacks, long pathPacksLoaded, long persistedPacks,
                        long entries, long diskBytes, long contentHits, long contentMisses,
                        long contentCaptures, long contentBytesServed, long retainedContentBytes,
                        long contentDiskBytes, long routeHits, long routeMisses, long routeRepairs,
                        long routeLayouts, long routeEntries, long routeDiskBytes,
                        long pathIndexHits, long pathIndexMisses, long asyncScans) {
    }
}

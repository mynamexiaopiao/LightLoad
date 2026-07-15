package com.lightload.core;

import com.lightload.LightloadMod;
import com.lightload.cache.ResourceIndexCache;
import com.lightload.config.LightloadConfig;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.lang.management.ManagementFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class StartupCoordinator {
    private static final String MEMORY_SETTINGS_ERROR_SCREEN = "com.memorysettings.MemoryErrorScreen";
    private static final AtomicBoolean INITIALIZED = new AtomicBoolean();
    private static final AtomicBoolean COMPLETED = new AtomicBoolean();
    private static final AtomicBoolean RELOAD_RECORDED = new AtomicBoolean();
    private static final AtomicLong MINECRAFT_STARTED = new AtomicLong();
    private static final AtomicLong MINECRAFT_FINISHED = new AtomicLong();
    private static final AtomicLong RELOAD_STARTED = new AtomicLong();
    private static final AtomicLong RELOAD_FINISHED = new AtomicLong();
    private static final AtomicLong STARTUP_FINISHED = new AtomicLong();
    private static volatile LightloadConfig config = new LightloadConfig(false, false, false, false, false, 0);
    private static volatile ResourceIndexCache cache;
    private static volatile Path startupLock;
    private static volatile Path warmupLock;
    private static volatile boolean recoveryMode;
    private static volatile boolean aggressiveRecoveryMode;
    private static volatile boolean optimizationsActive;

    private StartupCoordinator() {
    }

    public static void initialize() {
        if (INITIALIZED.get()) return;
        if (!INITIALIZED.compareAndSet(false, true)) return;
        Path gameDirectory = FMLPaths.GAMEDIR.get();
        Path cacheDirectory = gameDirectory.resolve("cache").resolve("lightload");
        startupLock = cacheDirectory.resolve("startup.lock");
        warmupLock = cacheDirectory.resolve("warmup.lock");
        config = LightloadConfig.load(FMLPaths.CONFIGDIR.get());
        boolean systemDisabled = Boolean.getBoolean("lightload.disable");
        recoveryMode = Files.isRegularFile(startupLock);
        aggressiveRecoveryMode = !recoveryMode && Files.isRegularFile(warmupLock);

        if (systemDisabled || !config.enabled()) {
            LightloadMod.LOGGER.info("[Lightload] Startup optimizations are disabled");
            return;
        }
        if (recoveryMode) {
            LightloadMod.LOGGER.warn("[Lightload] Previous startup did not complete; cache optimizations are disabled for this run");
            consumeRecoveryMarkers();
            return;
        }
        if (aggressiveRecoveryMode) {
            LightloadMod.LOGGER.warn("[Lightload] Previous background warmup did not finish; aggressive optimizations are disabled for this run");
            try {
                Files.deleteIfExists(warmupLock);
            } catch (IOException ignored) {
            }
        }

        try {
            Files.createDirectories(cacheDirectory);
            Files.writeString(startupLock, Long.toString(System.currentTimeMillis()),
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            if (config.aggressiveStartup() && !aggressiveRecoveryMode) {
                Files.writeString(warmupLock, Long.toString(System.currentTimeMillis()),
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            }
        } catch (IOException exception) {
            LightloadMod.LOGGER.warn("[Lightload] Could not create startup recovery marker; continuing without it", exception);
        }
        cache = new ResourceIndexCache(config, cacheDirectory);
        optimizationsActive = cache.isUsable();
        if (CompatibilityManager.modernFixPresent()) {
            LightloadMod.LOGGER.info("[Lightload] ModernFix owns DFU, reload executor, stitcher, and model layers; Lightload resource caches remain active");
        }
        LightloadMod.LOGGER.info("[Lightload] Adaptive startup optimizations are enabled");
    }

    public static boolean optimizationsEnabled() {
        if (!INITIALIZED.get()) initialize();
        return optimizationsActive;
    }

    public static boolean optimizationsActive() {
        return optimizationsActive;
    }

    public static ResourceIndexCache cache() {
        if (!INITIALIZED.get()) initialize();
        return cache;
    }

    public static boolean shouldShowLoadingTime() {
        initialize();
        return config.showLoadingTime() && !CompatibilityManager.lightspeedPresent();
    }

    public static boolean shouldUseDetailedReloadProfiling() {
        return false;
    }

    public static boolean shouldIsolateModdedReloadFailures() {
        initialize();
        return optimizationsActive && config.isolateModdedResourceReloadFailures()
                && (!config.connectorCompatibilityMode() || !CompatibilityManager.connectorPresent());
    }

    public static LightloadConfig config() {
        if (!INITIALIZED.get()) initialize();
        return config;
    }

    public static boolean aggressiveOptimizationsEnabled() {
        if (!INITIALIZED.get()) initialize();
        return optimizationsActive && config.aggressiveStartup() && !aggressiveRecoveryMode;
    }

    public static String loadingTimeText() {
        long finishedMillis = STARTUP_FINISHED.get();
        return "Lightload " + formatDuration(finishedMillis == 0 ? uptimeMillis() : finishedMillis);
    }

    public static String completedStartupTimeText() {
        long finished = STARTUP_FINISHED.get();
        if (finished == 0) {
            STARTUP_FINISHED.compareAndSet(0, uptimeMillis());
            finished = STARTUP_FINISHED.get();
        }
        return formatDuration(finished);
    }

    public static void minecraftConstructionStarted() {
        initialize();
        MINECRAFT_STARTED.compareAndSet(0, System.nanoTime());
    }

    public static void minecraftConstructionFinished() {
        MINECRAFT_FINISHED.compareAndSet(0, System.nanoTime());
    }

    public static void resourceReloadStarted() {
        if (RELOAD_RECORDED.compareAndSet(false, true)) {
            RELOAD_STARTED.set(System.nanoTime());
        }
    }

    public static void resourceReloadFinished() {
        if (RELOAD_STARTED.get() != 0 && RELOAD_FINISHED.compareAndSet(0, System.nanoTime())) {
        }
    }

    public static void startupCompleted() {
        initialize();
        if (!COMPLETED.compareAndSet(false, true)) return;
        if (config.progressiveModelWarmup() && aggressiveOptimizationsEnabled()) {
            LazyDataFixerManager.prewarmAsync(StartupCoordinator::aggressiveWarmupCompleted);
        } else {
            aggressiveWarmupCompleted();
        }
        ResourceIndexCache currentCache = cache;
        if (currentCache != null) currentCache.persistAsync();
        try {
            if (startupLock != null) Files.deleteIfExists(startupLock);
            Path agentGameDirectory = Path.of(System.getProperty("lightload.agent.gameDir", FMLPaths.GAMEDIR.get().toString()));
            Files.deleteIfExists(agentGameDirectory.resolve("cache").resolve("lightload")
                    .resolve("early").resolve("early-agent.lock"));
        } catch (IOException exception) {
            LightloadMod.LOGGER.warn("[Lightload] Could not clear startup recovery marker", exception);
        }
        if (config.profiling()) logSummary(currentCache);
        VoxelShapeStartupCache.finishStartup();
        StartupNetworkGuard.releaseDeferredRequests();
    }

    public static void aggressiveWarmupCompleted() {
        try {
            if (warmupLock != null) Files.deleteIfExists(warmupLock);
        } catch (IOException exception) {
            LightloadMod.LOGGER.debug("[Lightload] Could not clear warmup marker", exception);
        }
    }

    private static void consumeRecoveryMarkers() {
        try {
            if (startupLock != null) Files.deleteIfExists(startupLock);
            if (warmupLock != null) Files.deleteIfExists(warmupLock);
        } catch (IOException exception) {
            LightloadMod.LOGGER.warn("[Lightload] Could not consume startup recovery marker", exception);
        }
    }

    public static void startupScreenShown(String screenClassName) {
        if (isStartupCompletionScreen(screenClassName)) {
            freezeStartupTimer();
            startupCompleted();
        }
    }

    public static void titleScreenInitialized() {
        freezeStartupTimer();
        startupCompleted();
    }

    public static boolean startupIsComplete() {
        return COMPLETED.get();
    }

    public static boolean shouldDeferRemoteConfigDownloads() {
        initialize();
        return optimizationsActive && config.deferRemoteConfigDownloads() && !COMPLETED.get();
    }

    public static boolean shouldGuardOptionalStartupNetwork() {
        initialize();
        return optionalNetworkGuardEnabled(Boolean.getBoolean("lightload.disable"),
                config.deferRemoteConfigDownloads(), COMPLETED.get());
    }

    static boolean optionalNetworkGuardEnabled(boolean systemDisabled, boolean configured, boolean startupComplete) {
        return !systemDisabled && configured && !startupComplete;
    }

    public static boolean shouldDeferYaclImagePreload() {
        initialize();
        return optimizationsActive && config.deferYaclImagePreload() && !COMPLETED.get();
    }

    public static boolean lazyYaclImagesEnabled() {
        initialize();
        return optimizationsActive && config.deferYaclImagePreload();
    }

    private static void freezeStartupTimer() {
        if (STARTUP_FINISHED.compareAndSet(0, uptimeMillis())) {
            LightloadMod.LOGGER.info("[Lightload] Launch took {}", formatDuration(STARTUP_FINISHED.get()));
        }
    }

    static boolean isStartupCompletionScreen(String screenClassName) {
        return MEMORY_SETTINGS_ERROR_SCREEN.equals(screenClassName);
    }

    private static void logSummary(ResourceIndexCache currentCache) {
        long total = STARTUP_FINISHED.get() == 0 ? uptimeMillis() : STARTUP_FINISHED.get();
        long construction = elapsedMillis(MINECRAFT_STARTED.get(), MINECRAFT_FINISHED.get());
        long reload = elapsedMillis(RELOAD_STARTED.get(), RELOAD_FINISHED.get());
        ResourceIndexCache.Stats stats = currentCache == null
                ? new ResourceIndexCache.Stats(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)
                : currentCache.stats();
        LightloadMod.LOGGER.info(
                "[Lightload] Startup summary: observed={} ms, minecraft={} ms, firstReload={} ms, " +
                        "existenceHits={}, existenceMisses={}, cachePacksLoaded={}, pathPacksLoaded={}, cachePacksPersisted={}, " +
                        "existenceEntries={}, cacheDisk={} KiB, contentHits={}, contentMisses={}, contentCaptures={}, " +
                        "contentServed={} KiB, contentMemory={} MiB, contentDisk={} MiB, " +
                        "routeHits={}, routeMisses={}, routeRepairs={}, routeLayouts={}, routeEntries={}, routeDisk={} KiB, " +
                        "pathIndexHits={}, pathIndexMisses={}, asyncPathScans={}, " +
                        "aggressive={}, reloadExecutor={}, lazyDfu={}, fastStitch={}, " +
                        "modernFix={}, optimizationProfile={}, recovery={}, earlyAgent={}, agentCache={}, agentCacheReason={}, agentCacheRecordReason={}, " +
                        "runtimeAgent={}, runtimePatch={}, mixinOpcodeCache={}, mixinTargetIndex={}, oculusPathPatch={}, " +
                        "mixinTargetConfigs={}, mixinTargetClasses={}, modulePackageIndex={}, modulePackages={}, " +
                        "modulePackageHits={}, modulePackageMisses={}, moduleClosure={}, moduleClosureSkips={}, " +
                        "moduleClosureFallbacks={}, moduleClosureGraphSize={}, " +
                        "lazyBlockStateCache={}, deferredBlockStateCaches={}, generatedBlockStateCaches={}, " +
                        "deferRemoteDownloads={}, deferredRemoteDownloads={}, " +
                        "forgeRegistryCache={}, registryCacheHits={}, registryCacheMisses={}, registryCacheEntries={}, " +
                        "shapeCacheHits={}, shapeCacheMisses={}, shapeCacheEntries={}, shapeCacheRejected={}, " +
                        "routeCache={}, packExistenceCache={}, existenceCache={}, contentCache={}, parallelResourceLookup={}, parallelLookups={}, lightspeedInterop={}, " +
                        "effectiveReloadExecutor={}, effectiveLazyDfu={}, effectiveFastStitch={}",
                total, construction, reload, stats.hits(), stats.misses(), stats.loadedPacks(),
                stats.pathPacksLoaded(), stats.persistedPacks(),
                stats.entries(), stats.diskBytes() / 1024, stats.contentHits(), stats.contentMisses(),
                stats.contentCaptures(), stats.contentBytesServed() / 1024,
                stats.retainedContentBytes() / 1024 / 1024, stats.contentDiskBytes() / 1024 / 1024,
                stats.routeHits(), stats.routeMisses(), stats.routeRepairs(), stats.routeLayouts(), stats.routeEntries(),
                stats.routeDiskBytes() / 1024,
                stats.pathIndexHits(), stats.pathIndexMisses(), stats.asyncScans(),
                aggressiveOptimizationsEnabled(),
                config.dedicatedReloadExecutor(), config.lazyDataFixer(), config.fastTextureStitching(),
                CompatibilityManager.modernFixPresent(), CompatibilityManager.optimizationProfile(config), recoveryMode,
                Boolean.getBoolean("lightload.agent.active"), System.getProperty("lightload.agent.cache", "inactive"),
                System.getProperty("lightload.agent.cache.reason", "none"),
                System.getProperty("lightload.agent.cache.recordReason", "none"),
                Boolean.getBoolean("lightload.runtimeAgent.active"),
                System.getProperty("lightload.runtimeAgent.status", "inactive"),
                System.getProperty("lightload.runtimePatch.mixinOpcode", "inactive"),
                System.getProperty("lightload.runtimePatch.mixinTargets", "inactive"),
                System.getProperty("lightload.runtimePatch.oculusPath", "inactive"),
                System.getProperty("lightload.runtimePatch.mixinTargetConfigs", "0"),
                System.getProperty("lightload.runtimePatch.mixinTargetClasses", "0"),
                System.getProperty("lightload.runtimePatch.modulePackageIndex", "inactive"),
                System.getProperty("lightload.runtimePatch.modulePackages", "inactive"),
                System.getProperty("lightload.runtimePatch.modulePackageHits", "0"),
                System.getProperty("lightload.runtimePatch.modulePackageMisses", "0"),
                System.getProperty("lightload.runtimePatch.moduleClosure", "inactive"),
                System.getProperty("lightload.runtimePatch.moduleClosureSkips", "0"),
                System.getProperty("lightload.runtimePatch.moduleClosureFallbacks", "0"),
                System.getProperty("lightload.runtimePatch.moduleClosureGraphSize", "0"),
                LazyBlockStateCache.enabled(), LazyBlockStateCache.deferredCaches(),
                LazyBlockStateCache.generatedCaches(),
                config.deferRemoteConfigDownloads(), StartupNetworkGuard.deferredRequests(),
                ForgeRegistryExistenceCache.enabled(), ForgeRegistryExistenceCache.hits(),
                ForgeRegistryExistenceCache.misses(), ForgeRegistryExistenceCache.entries(),
                VoxelShapeStartupCache.hits(), VoxelShapeStartupCache.misses(), VoxelShapeStartupCache.entries(),
                VoxelShapeStartupCache.rejected(),
                currentCache != null && currentCache.routeCacheEnabled(),
                currentCache != null && currentCache.packExistenceCacheEnabled(),
                currentCache != null && currentCache.existenceCacheEnabled(),
                currentCache != null && currentCache.contentCacheEnabled(),
                ResourceLookupOptimizer.enabled(), ResourceLookupOptimizer.parallelLookups(),
                CompatibilityManager.lightspeedModernFixInteropEnabled(config),
                config.dedicatedReloadExecutor() && !CompatibilityManager.modernFixPresent(),
                config.lazyDataFixer() && !CompatibilityManager.modernFixPresent(),
                config.fastTextureStitching() && !CompatibilityManager.modernFixPresent()
        );
    }

    private static long elapsedMillis(long start, long end) {
        return start == 0 || end == 0 || end < start ? -1 : (end - start) / 1_000_000L;
    }

    private static String formatDuration(long elapsedMillis) {
        return Math.max(0, elapsedMillis) / 1000L + "s";
    }

    static long uptimeMillis() {
        return ManagementFactory.getRuntimeMXBean().getUptime();
    }
}

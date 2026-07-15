package com.lightload.config;

import com.lightload.LightloadMod;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

public record LightloadConfig(
        boolean enabled,
        boolean memoryCache,
        boolean persistentCache,
        boolean profiling,
        boolean showLoadingTime,
        boolean aggressiveStartup,
        boolean dedicatedReloadExecutor,
        int reloadThreads,
        boolean lazyDataFixer,
        boolean progressiveModelWarmup,
        boolean fastTextureStitching,
        boolean lazyBlockStateCache,
        boolean deferRemoteConfigDownloads,
        boolean deferYaclImagePreload,
        boolean forgeRegistryLookupCache,
        boolean mixinTargetIndex,
        boolean lightspeedModernFixInterop,
        boolean cacheResourceRoutes,
        boolean cacheResourceExistence,
        boolean cacheResourceContent,
        long maxContentCacheBytes,
        int maxCachedResourceBytes,
        boolean parallelResourceLookup,
        int parallelLookupMinPacks,
        boolean isolateModdedResourceReloadFailures,
        String isolatedResourceReloadListenerPatterns,
        boolean connectorCompatibilityMode,
        String optimizationProfile
) {
    public static LightloadConfig load(Path configDirectory) {
        Path path = configDirectory.resolve("lightload.properties");
        Properties defaults = defaults();
        Properties loaded = new Properties();
        if (Files.isRegularFile(path)) {
            try (InputStream input = Files.newInputStream(path)) {
                loaded.load(input);
            } catch (IOException | IllegalArgumentException exception) {
                LightloadMod.LOGGER.warn("[Lightload] Could not read {}; using defaults", path, exception);
                loaded.clear();
            }
        }

        Properties properties = new Properties();
        for (String key : defaults.stringPropertyNames()) {
            properties.setProperty(key, loaded.getProperty(key, defaults.getProperty(key)));
        }
        if (!Files.isRegularFile(path) || !loaded.equals(properties)) write(path, properties);

        return new LightloadConfig(
                bool(properties, "enabled", true),
                bool(properties, "memoryCache", true),
                bool(properties, "persistentCache", true),
                bool(properties, "profiling", true),
                bool(properties, "showLoadingTime", true),
                bool(properties, "aggressiveStartup", true),
                bool(properties, "dedicatedReloadExecutor", true),
                (int) clamp(number(properties, "reloadThreads", 0), 0, 64),
                bool(properties, "lazyDataFixer", true),
                bool(properties, "progressiveModelWarmup", true),
                bool(properties, "fastTextureStitching", true),
                bool(properties, "lazyBlockStateCache", true),
                bool(properties, "deferRemoteConfigDownloads", true),
                bool(properties, "deferYaclImagePreload", true),
                bool(properties, "forgeRegistryLookupCache", true),
                bool(properties, "mixinTargetIndex", true),
                bool(properties, "lightspeedModernFixInterop", true),
                bool(properties, "cacheResourceRoutes", false),
                bool(properties, "cacheResourceExistence", true),
                bool(properties, "cacheResourceContent", false),
                clamp(number(properties, "maxContentCacheMiB", 256), 16, 1024) * 1024L * 1024L,
                (int) (clamp(number(properties, "maxCachedResourceKiB", 1024), 16, 8192) * 1024L),
                bool(properties, "parallelResourceLookup", true),
                (int) clamp(number(properties, "parallelLookupMinPacks", 4), 2, 64),
                bool(properties, "isolateModdedResourceReloadFailures", true),
                properties.getProperty("isolatedResourceReloadListenerPatterns", "*"),
                bool(properties, "connectorCompatibilityMode", true),
                profile(properties.getProperty("optimizationProfile")));
    }

    public LightloadConfig(boolean enabled, boolean memoryCache, boolean persistentCache,
                           boolean profiling, long ignoredCacheLimit) {
        this(enabled, memoryCache, persistentCache, profiling, true, true, true, 0,
                true, true, true, true, true, true, true, true, true, true, true, true,
                256L * 1024 * 1024, 1024 * 1024, true, 4, true, "*", true, "auto");
    }

    public LightloadConfig(boolean enabled, boolean memoryCache, boolean persistentCache,
                           boolean profiling, long ignoredCacheLimit, boolean cacheResourceExistence) {
        this(enabled, memoryCache, persistentCache, profiling, true, true, true, 0,
                true, true, true, true, true, true, true, true, true, false, cacheResourceExistence, true,
                256L * 1024 * 1024, 1024 * 1024, true, 4, true, "*", true, "auto");
    }

    public LightloadConfig(boolean enabled, boolean memoryCache, boolean persistentCache,
                           boolean profiling, boolean showLoadingTime, long ignoredCacheLimit) {
        this(enabled, memoryCache, persistentCache, profiling, showLoadingTime, true, true, 0,
                true, true, true, true, true, true, true, true, true, true, true, true,
                256L * 1024 * 1024, 1024 * 1024, true, 4, true, "*", true, "auto");
    }

    private static Properties defaults() {
        Properties properties = new Properties();
        properties.setProperty("enabled", "true");
        properties.setProperty("memoryCache", "true");
        properties.setProperty("persistentCache", "true");
        properties.setProperty("cacheResourceRoutes", "false");
        properties.setProperty("cacheResourceExistence", "true");
        properties.setProperty("cacheResourceContent", "false");
        properties.setProperty("maxContentCacheMiB", "256");
        properties.setProperty("maxCachedResourceKiB", "1024");
        properties.setProperty("parallelResourceLookup", "true");
        properties.setProperty("parallelLookupMinPacks", "4");
        properties.setProperty("isolateModdedResourceReloadFailures", "true");
        properties.setProperty("isolatedResourceReloadListenerPatterns", "*");
        properties.setProperty("connectorCompatibilityMode", "true");
        properties.setProperty("dedicatedReloadExecutor", "true");
        properties.setProperty("reloadThreads", "0");
        properties.setProperty("lazyDataFixer", "true");
        properties.setProperty("progressiveModelWarmup", "true");
        properties.setProperty("fastTextureStitching", "true");
        properties.setProperty("lazyBlockStateCache", "true");
        properties.setProperty("deferRemoteConfigDownloads", "true");
        properties.setProperty("deferYaclImagePreload", "true");
        properties.setProperty("forgeRegistryLookupCache", "true");
        properties.setProperty("voxelShapeCache", "true");
        properties.setProperty("mixinTargetIndex", "true");
        properties.setProperty("oculusPathValidationPatch", "true");
        properties.setProperty("aggressiveStartup", "true");
        properties.setProperty("profiling", "true");
        properties.setProperty("showLoadingTime", "true");
        properties.setProperty("lightspeedModernFixInterop", "true");
        properties.setProperty("optimizationProfile", "auto");
        // Read by the optional premain agent before Forge constructs this record.
        properties.setProperty("earlyAgent", "true");
        properties.setProperty("parallelJarJarMetadata", "true");
        properties.setProperty("jarJarThreads", "4");
        properties.setProperty("runtimeTransformerPatches", "true");
        return properties;
    }

    private static void write(Path path, Properties properties) {
        try {
            Files.createDirectories(path.getParent());
            Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
            try (OutputStream output = Files.newOutputStream(temporary)) {
                properties.store(output, "Lightload core startup optimization settings");
            }
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException ignored) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            LightloadMod.LOGGER.warn("[Lightload] Could not write {}", path, exception);
        }
    }

    private static boolean bool(Properties properties, String key, boolean fallback) {
        String value = properties.getProperty(key);
        return value == null ? fallback : Boolean.parseBoolean(value.trim());
    }

    private static long number(Properties properties, String key, long fallback) {
        try {
            return Long.parseLong(properties.getProperty(key, Long.toString(fallback)).trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static long clamp(long value, long minimum, long maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private static String profile(String value) {
        if (value == null) return "auto";
        String normalized = value.trim().toLowerCase(java.util.Locale.ROOT);
        return normalized.equals("lightload_full") ? normalized : "auto";
    }
}

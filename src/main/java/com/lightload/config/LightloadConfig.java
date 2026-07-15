package com.lightload.config;

import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.TranslatableEnum;

import java.util.List;
import java.util.Locale;

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
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();
    public static final Values VALUES = new Values(BUILDER);
    public static final ModConfigSpec SPEC = BUILDER.build();

    public static LightloadConfig current() {
        if (!SPEC.isLoaded()) return defaults();
        return new LightloadConfig(
                VALUES.enabled.get(), VALUES.memoryCache.get(), VALUES.persistentCache.get(),
                VALUES.profiling.get(), VALUES.showLoadingTime.get(), VALUES.aggressiveStartup.get(),
                VALUES.dedicatedReloadExecutor.get(), VALUES.reloadThreads.get(), VALUES.lazyDataFixer.get(),
                VALUES.progressiveModelWarmup.get(), VALUES.fastTextureStitching.get(),
                VALUES.lazyBlockStateCache.get(), VALUES.deferRemoteConfigDownloads.get(),
                VALUES.deferYaclImagePreload.get(), VALUES.forgeRegistryLookupCache.get(),
                VALUES.mixinTargetIndex.get(), VALUES.lightspeedModernFixInterop.get(),
                VALUES.cacheResourceRoutes.get(), VALUES.cacheResourceExistence.get(),
                VALUES.cacheResourceContent.get(), VALUES.maxContentCacheMiB.get() * 1024L * 1024L,
                VALUES.maxCachedResourceKiB.get() * 1024, VALUES.parallelResourceLookup.get(),
                VALUES.parallelLookupMinPacks.get(), VALUES.isolateModdedResourceReloadFailures.get(),
                VALUES.isolatedResourceReloadListenerPatterns.get(), VALUES.connectorCompatibilityMode.get(),
                profile(VALUES.optimizationProfile.get().name()));
    }

    public static boolean loaded() {
        return SPEC.isLoaded();
    }

    private static LightloadConfig defaults() {
        return new LightloadConfig(
                true, true, true, true, true, true, true, 0,
                true, true, true, true, true, true, true, true, true,
                false, true, false, 256L * 1024 * 1024, 1024 * 1024,
                true, 4, true, "*", true, "auto");
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

    private static String profile(String value) {
        String normalized = value == null ? "auto" : value.trim().toLowerCase(Locale.ROOT);
        return List.of("auto", "lightload_full", "conservative").contains(normalized) ? normalized : "auto";
    }

    public static final class Values {
        public final ModConfigSpec.BooleanValue enabled;
        public final ModConfigSpec.BooleanValue memoryCache;
        public final ModConfigSpec.BooleanValue persistentCache;
        public final ModConfigSpec.BooleanValue profiling;
        public final ModConfigSpec.BooleanValue showLoadingTime;
        public final ModConfigSpec.BooleanValue aggressiveStartup;
        public final ModConfigSpec.BooleanValue dedicatedReloadExecutor;
        public final ModConfigSpec.IntValue reloadThreads;
        public final ModConfigSpec.BooleanValue lazyDataFixer;
        public final ModConfigSpec.BooleanValue progressiveModelWarmup;
        public final ModConfigSpec.BooleanValue fastTextureStitching;
        public final ModConfigSpec.BooleanValue lazyBlockStateCache;
        public final ModConfigSpec.BooleanValue deferRemoteConfigDownloads;
        public final ModConfigSpec.BooleanValue deferYaclImagePreload;
        public final ModConfigSpec.BooleanValue forgeRegistryLookupCache;
        public final ModConfigSpec.BooleanValue mixinTargetIndex;
        public final ModConfigSpec.BooleanValue lightspeedModernFixInterop;
        public final ModConfigSpec.BooleanValue cacheResourceRoutes;
        public final ModConfigSpec.BooleanValue cacheResourceExistence;
        public final ModConfigSpec.BooleanValue cacheResourceContent;
        public final ModConfigSpec.IntValue maxContentCacheMiB;
        public final ModConfigSpec.IntValue maxCachedResourceKiB;
        public final ModConfigSpec.BooleanValue parallelResourceLookup;
        public final ModConfigSpec.IntValue parallelLookupMinPacks;
        public final ModConfigSpec.BooleanValue isolateModdedResourceReloadFailures;
        public final ModConfigSpec.ConfigValue<String> isolatedResourceReloadListenerPatterns;
        public final ModConfigSpec.BooleanValue connectorCompatibilityMode;
        public final ModConfigSpec.EnumValue<OptimizationProfile> optimizationProfile;

        // Read directly from lightload-client.toml by the optional premain/runtime agent.
        public final ModConfigSpec.BooleanValue earlyAgent;
        public final ModConfigSpec.BooleanValue parallelJarJarMetadata;
        public final ModConfigSpec.IntValue jarJarThreads;
        public final ModConfigSpec.BooleanValue runtimeTransformerPatches;
        public final ModConfigSpec.BooleanValue voxelShapeCache;
        public final ModConfigSpec.BooleanValue oculusPathValidationPatch;

        private Values(ModConfigSpec.Builder builder) {
            builder.comment("General Lightload client settings")
                    .translation("lightload.configuration.section.general").push("general");
            enabled = builder.translation("lightload.configuration.general.enabled").define("enabled", true);
            profiling = builder.translation("lightload.configuration.general.profiling").define("profiling", true);
            showLoadingTime = builder.translation("lightload.configuration.general.showLoadingTime").define("showLoadingTime", true);
            aggressiveStartup = builder.translation("lightload.configuration.general.aggressiveStartup").define("aggressiveStartup", true);
            optimizationProfile = builder.translation("lightload.configuration.general.optimizationProfile")
                    .defineEnum("optimizationProfile", OptimizationProfile.AUTO);
            builder.pop();

            builder.comment("Resource indexing and reload optimizations")
                    .translation("lightload.configuration.section.resources").push("resources");
            memoryCache = builder.translation("lightload.configuration.resources.memoryCache").define("memoryCache", true);
            persistentCache = builder.translation("lightload.configuration.resources.persistentCache").define("persistentCache", true);
            cacheResourceRoutes = builder.translation("lightload.configuration.resources.cacheResourceRoutes").define("cacheResourceRoutes", false);
            cacheResourceExistence = builder.translation("lightload.configuration.resources.cacheResourceExistence").define("cacheResourceExistence", true);
            cacheResourceContent = builder.translation("lightload.configuration.resources.cacheResourceContent").define("cacheResourceContent", false);
            maxContentCacheMiB = builder.translation("lightload.configuration.resources.maxContentCacheMiB").defineInRange("maxContentCacheMiB", 256, 16, 1024);
            maxCachedResourceKiB = builder.translation("lightload.configuration.resources.maxCachedResourceKiB").defineInRange("maxCachedResourceKiB", 1024, 16, 8192);
            parallelResourceLookup = builder.translation("lightload.configuration.resources.parallelResourceLookup").define("parallelResourceLookup", true);
            parallelLookupMinPacks = builder.translation("lightload.configuration.resources.parallelLookupMinPacks").defineInRange("parallelLookupMinPacks", 4, 2, 64);
            dedicatedReloadExecutor = builder.translation("lightload.configuration.resources.dedicatedReloadExecutor").define("dedicatedReloadExecutor", true);
            reloadThreads = builder.translation("lightload.configuration.resources.reloadThreads").defineInRange("reloadThreads", 0, 0, 64);
            isolateModdedResourceReloadFailures = builder.translation("lightload.configuration.resources.isolateModdedResourceReloadFailures").define("isolateModdedResourceReloadFailures", true);
            isolatedResourceReloadListenerPatterns = builder.translation("lightload.configuration.resources.isolatedResourceReloadListenerPatterns").define("isolatedResourceReloadListenerPatterns", "*");
            builder.pop();

            builder.comment("Startup optimizations")
                    .translation("lightload.configuration.section.startup").push("startup");
            lazyDataFixer = builder.translation("lightload.configuration.startup.lazyDataFixer").define("lazyDataFixer", true);
            progressiveModelWarmup = builder.translation("lightload.configuration.startup.progressiveModelWarmup").define("progressiveModelWarmup", true);
            fastTextureStitching = builder.translation("lightload.configuration.startup.fastTextureStitching").define("fastTextureStitching", true);
            lazyBlockStateCache = builder.translation("lightload.configuration.startup.lazyBlockStateCache").define("lazyBlockStateCache", true);
            deferRemoteConfigDownloads = builder.translation("lightload.configuration.startup.deferRemoteConfigDownloads").define("deferRemoteConfigDownloads", true);
            deferYaclImagePreload = builder.translation("lightload.configuration.startup.deferYaclImagePreload").define("deferYaclImagePreload", true);
            forgeRegistryLookupCache = builder.translation("lightload.configuration.startup.forgeRegistryLookupCache").define("forgeRegistryLookupCache", true);
            mixinTargetIndex = builder.translation("lightload.configuration.startup.mixinTargetIndex").define("mixinTargetIndex", true);
            voxelShapeCache = builder.translation("lightload.configuration.startup.voxelShapeCache").define("voxelShapeCache", true);
            builder.pop();

            builder.comment("Compatibility switches")
                    .translation("lightload.configuration.section.compatibility").push("compatibility");
            connectorCompatibilityMode = builder.translation("lightload.configuration.compatibility.connectorCompatibilityMode").define("connectorCompatibilityMode", true);
            lightspeedModernFixInterop = builder.translation("lightload.configuration.compatibility.lightspeedModernFixInterop").define("lightspeedModernFixInterop", true);
            oculusPathValidationPatch = builder.translation("lightload.configuration.compatibility.oculusPathValidationPatch").define("oculusPathValidationPatch", true);
            builder.pop();

            builder.comment("Optional Java agent settings")
                    .translation("lightload.configuration.section.agent").push("agent");
            earlyAgent = builder.translation("lightload.configuration.agent.earlyAgent").define("earlyAgent", true);
            parallelJarJarMetadata = builder.translation("lightload.configuration.agent.parallelJarJarMetadata").define("parallelJarJarMetadata", false);
            jarJarThreads = builder.translation("lightload.configuration.agent.jarJarThreads").defineInRange("jarJarThreads", 4, 1, 8);
            runtimeTransformerPatches = builder.translation("lightload.configuration.agent.runtimeTransformerPatches").define("runtimeTransformerPatches", true);
            builder.pop();
        }
    }

    public enum OptimizationProfile implements TranslatableEnum {
        AUTO("auto"),
        LIGHTLOAD_FULL("lightload_full"),
        CONSERVATIVE("conservative");

        private final String translationName;

        OptimizationProfile(String translationName) {
            this.translationName = translationName;
        }

        @Override
        public Component getTranslatedName() {
            return Component.translatable("lightload.configuration.value.optimizationProfile." + translationName);
        }
    }
}

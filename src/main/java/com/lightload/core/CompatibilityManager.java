package com.lightload.core;

import com.lightload.config.LightloadConfig;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.loading.FMLLoader;

public final class CompatibilityManager {
    private static volatile Boolean modernFixPresent;
    private static volatile Boolean lightspeedPresent;
    private static volatile Boolean cableFacadesPresent;
    private static volatile Boolean titaniumPresent;
    private static volatile Boolean framedBlocksPresent;
    private static volatile Boolean adAstraPresent;
    private static volatile Boolean travelersBackpackPresent;
    private static volatile Boolean probeJsPresent;
    private static volatile Boolean waterMediaYoutubePresent;
    private static volatile Boolean fabricResourceLoaderPresent;

    private CompatibilityManager() {
    }

    public static boolean modernFixPresent() {
        Boolean cached = modernFixPresent;
        if (cached != null) return cached;
        try {
            if (isLoaded("modernfix")) {
                modernFixPresent = true;
                return true;
            }
            return false;
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    public static boolean optiFinePresent() {
        return isLoaded("optifine") || classPresent("optifine.OptiFineTransformationService");
    }

    public static boolean lightspeedPresent() {
        Boolean cached = lightspeedPresent;
        if (cached != null) return cached;
        if (isLoaded("lightspeed")) {
            lightspeedPresent = true;
            return true;
        }
        return false;
    }

    public static boolean cableFacadesPresent() {
        Boolean cached = cableFacadesPresent;
        if (cached != null) return cached;
        if (isLoaded("cable_facades")) {
            cableFacadesPresent = true;
            return true;
        }
        return false;
    }

    public static boolean titaniumPresent() {
        Boolean cached = titaniumPresent;
        if (cached != null) return cached;
        if (isLoaded("titanium")) {
            titaniumPresent = true;
            return true;
        }
        return false;
    }

    public static boolean yaclPresent() {
        return isLoaded("yet_another_config_lib_v3");
    }

    public static boolean embeddiumPresent() {
        return isLoaded("embeddium");
    }

    public static boolean framedBlocksPresent() {
        Boolean cached = framedBlocksPresent;
        if (cached != null) return cached;
        if (isLoaded("framedblocks")) {
            framedBlocksPresent = true;
            return true;
        }
        return false;
    }

    public static boolean adAstraPresent() {
        Boolean cached = adAstraPresent;
        if (cached != null) return cached;
        if (isLoaded("ad_astra")) {
            adAstraPresent = true;
            return true;
        }
        return false;
    }

    public static boolean travelersBackpackPresent() {
        Boolean cached = travelersBackpackPresent;
        if (cached != null) return cached;
        if (isLoaded("travelersbackpack")) {
            travelersBackpackPresent = true;
            return true;
        }
        return false;
    }

    public static boolean probeJsPresent() {
        Boolean cached = probeJsPresent;
        if (cached != null) return cached;
        if (isLoaded("probejs")) {
            probeJsPresent = true;
            return true;
        }
        return false;
    }

    public static boolean waterMediaYoutubePresent() {
        Boolean cached = waterMediaYoutubePresent;
        if (cached != null) return cached;
        if (isLoaded("watermedia_youtube_plugin")) {
            waterMediaYoutubePresent = true;
            return true;
        }
        return false;
    }

    public static boolean modelCompatibilityRiskPresent() {
        return isLoaded("ctm") || isLoaded("ldlib") || isLoaded("custommachinery")
                || isLoaded("supermartijn642corelib") || isLoaded("multiblocked") || optiFinePresent();
    }

    public static boolean connectorPresent() {
        return isLoaded("connectormod") || isLoaded("connector");
    }

    public static boolean fabricResourceLoaderPresent() {
        Boolean cached = fabricResourceLoaderPresent;
        if (cached != null) return cached;
        if (isLoaded("fabric_resource_loader_v0") || isLoaded("forgified_fabric_api")
                || isLoaded("fabric_api")) {
            fabricResourceLoaderPresent = true;
            return true;
        }
        return false;
    }

    public static boolean resourcePathCompatibilityRiskPresent() {
        return connectorPresent() || (isLoaded("sophisticatedstorage") && isLoaded("jsonthings"));
    }

    public static OptimizationProfile optimizationProfile(LightloadConfig config) {
        String requested = config.optimizationProfile();
        if ("lightload_full".equals(requested)) return OptimizationProfile.VANILLA_STACK;
        if ("conservative".equals(requested) && modernFixPresent()) {
            return lightspeedPresent() ? OptimizationProfile.MODERNFIX_LIGHTSPEED_STACK : OptimizationProfile.MODERNFIX_STACK;
        }
        if (modernFixPresent() && lightspeedPresent()) return OptimizationProfile.MODERNFIX_LIGHTSPEED_STACK;
        if (modernFixPresent()) return OptimizationProfile.MODERNFIX_STACK;
        if (lightspeedPresent()) return OptimizationProfile.LIGHTSPEED_STACK;
        return OptimizationProfile.VANILLA_STACK;
    }

    public static boolean lightspeedModernFixInteropEnabled(LightloadConfig config) {
        return config.lightspeedModernFixInterop()
                && optimizationProfile(config) == OptimizationProfile.MODERNFIX_LIGHTSPEED_STACK;
    }

    public static boolean isLoaded(String modId) {
        try {
            ModList list = ModList.get();
            if (list != null && list.isLoaded(modId)) return true;
        } catch (RuntimeException | LinkageError ignored) {
        }
        try {
            return FMLLoader.getLoadingModList() != null
                    && FMLLoader.getLoadingModList().getModFileById(modId) != null;
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    private static boolean classPresent(String name) {
        try {
            Class.forName(name, false, CompatibilityManager.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError ignored) {
            return false;
        }
    }

    static void setModernFixPresentForTests(Boolean value) {
        modernFixPresent = value;
    }

    static void setLightspeedPresentForTests(Boolean value) {
        lightspeedPresent = value;
    }

    static void setCableFacadesPresentForTests(Boolean value) {
        cableFacadesPresent = value;
    }

    static void setTitaniumPresentForTests(Boolean value) {
        titaniumPresent = value;
    }

    static void setFramedBlocksPresentForTests(Boolean value) {
        framedBlocksPresent = value;
    }

    static void setAdAstraPresentForTests(Boolean value) {
        adAstraPresent = value;
    }

    static void setTravelersBackpackPresentForTests(Boolean value) {
        travelersBackpackPresent = value;
    }

    static void setProbeJsPresentForTests(Boolean value) {
        probeJsPresent = value;
    }

    static void setWaterMediaYoutubePresentForTests(Boolean value) {
        waterMediaYoutubePresent = value;
    }

    static void setFabricResourceLoaderPresentForTests(Boolean value) {
        fabricResourceLoaderPresent = value;
    }

    public enum OptimizationProfile {
        VANILLA_STACK,
        MODERNFIX_STACK,
        MODERNFIX_LIGHTSPEED_STACK,
        LIGHTSPEED_STACK
    }
}

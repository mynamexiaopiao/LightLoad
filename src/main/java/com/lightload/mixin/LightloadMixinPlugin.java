package com.lightload.mixin;

import com.lightload.core.CompatibilityManager;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

public final class LightloadMixinPlugin implements IMixinConfigPlugin {
    private static final String CABLE_FACADES_MIXIN =
            "com.lightload.mixin.compat.CableFacadesConfigMixin";
    private static final String MODERNFIX_MODEL_EVENT_MIXIN =
            "com.lightload.mixin.compat.ModernFixModelBakeEventHelperMixin";
    private static final String MODERNFIX_RESOURCE_NODE_MIXIN =
            "com.lightload.mixin.compat.ModernFixResourceNodeMixin";
    private static final String VOXEL_SHAPE_CACHE_MIXIN =
            "com.lightload.mixin.client.ShapesMixin";
    private static final String TITANIUM_REWARD_MIXIN =
            "com.lightload.mixin.compat.TitaniumRewardMixin";
    private static final String YACL_RELOAD_MIXIN =
            "com.lightload.mixin.compat.YaclImageReloadListenerMixin";
    private static final String YACL_MANAGER_MIXIN =
            "com.lightload.mixin.compat.YaclImageRendererManagerMixin";
    private static final String EMBEDDIUM_SRGB_MIXIN =
            "com.lightload.mixin.compat.EmbeddiumColorSrgbMixin";
    private static final String FRAMED_BLOCKS_STATE_CACHE_MIXIN =
            "com.lightload.mixin.compat.FramedBlocksStateCacheMixin";
    private static final String AD_ASTRA_STATIONS_MIXIN =
            "com.lightload.mixin.compat.AdAstraStationLoaderMixin";
    private static final String TRAVELERS_SUPPORTERS_MIXIN =
            "com.lightload.mixin.compat.TravelersBackpackSupportersMixin";
    private static final String PROBEJS_HTTP_CLIENT_MIXIN =
            "com.lightload.mixin.compat.ProbeJsHttpClientMixin";
    private static final String WATERMEDIA_HTTP_CLIENT_MIXIN =
            "com.lightload.mixin.compat.WaterMediaHttpClientMixin";
    private static final Set<String> MODERNFIX_OVERLAPS = Set.of(
            "com.lightload.mixin.client.MinecraftReloadExecutorMixin",
            "com.lightload.mixin.client.BlockEntityTypeMixin",
            "com.lightload.mixin.client.BlockStateBaseCacheMixin",
            "com.lightload.mixin.client.DataFixersMixin",
            "com.lightload.mixin.client.EntityTypeBuilderMixin",
            "com.lightload.mixin.client.GameDataBlockCallbacksMixin",
            "com.lightload.mixin.client.LdlibMetadataCacheMixin",
            "com.lightload.mixin.client.StitcherMixin"
    );
    private static final Set<String> DERIVED_MODEL_CACHES = Set.of(
            "com.lightload.mixin.client.MaterialHashMixin",
            "com.lightload.mixin.client.BlockModelMaterialCacheMixin",
            "com.lightload.mixin.client.MultiPartDependenciesMixin",
            "com.lightload.mixin.client.MultiVariantDependenciesMixin",
            "com.lightload.mixin.client.SelectorPredicateMixin"
    );
    private static final Set<String> LIGHTSPEED_CORE_OVERLAPS = Set.of(
            "com.lightload.mixin.client.DelegatingPackResourcesMixin",
            "com.lightload.mixin.client.FilePackResourcesMixin",
            "com.lightload.mixin.client.FallbackResourceManagerMixin",
            "com.lightload.mixin.client.FallbackResourceManagerPackEntryAccessor",
            "com.lightload.mixin.client.KeyValueConditionMixin",
            "com.lightload.mixin.client.PathPackResourcesMixin",
            "com.lightload.mixin.client.ResourcePackLoaderMixin",
            "com.lightload.mixin.client.ResourceLocationHashMixin",
            "com.lightload.mixin.client.SimpleReloadInstanceMixin",
            "com.lightload.mixin.client.StateDefinitionMixin",
            "com.lightload.mixin.client.VanillaPackResourcesMixin"
    );

    @Override
    public void onLoad(String mixinPackage) {
        if (Boolean.getBoolean("lightload.runtimeAgent.active")
                || Boolean.getBoolean("lightload.agent.active")) return;
        try {
            Class.forName("com.lightload.bootstrap.RuntimeAgentBootstrap", true,
                    LightloadMixinPlugin.class.getClassLoader())
                    .getMethod("install").invoke(null);
        } catch (ReflectiveOperationException ignored) {
        }
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (Boolean.getBoolean("lightload.disable")) return false;
        if ("com.lightload.mixin.compat.LightspeedMainMixin".equals(mixinClassName)) {
            return CompatibilityManager.modernFixPresent() && CompatibilityManager.lightspeedPresent();
        }
        if (CABLE_FACADES_MIXIN.equals(mixinClassName)) {
            return CompatibilityManager.cableFacadesPresent();
        }
        if (TITANIUM_REWARD_MIXIN.equals(mixinClassName)) {
            return CompatibilityManager.titaniumPresent();
        }
        if (YACL_RELOAD_MIXIN.equals(mixinClassName) || YACL_MANAGER_MIXIN.equals(mixinClassName)) {
            return CompatibilityManager.yaclPresent();
        }
        if (EMBEDDIUM_SRGB_MIXIN.equals(mixinClassName)) {
            return CompatibilityManager.embeddiumPresent();
        }
        if (FRAMED_BLOCKS_STATE_CACHE_MIXIN.equals(mixinClassName)) {
            return CompatibilityManager.framedBlocksPresent();
        }
        if (AD_ASTRA_STATIONS_MIXIN.equals(mixinClassName)) {
            return CompatibilityManager.adAstraPresent();
        }
        if (TRAVELERS_SUPPORTERS_MIXIN.equals(mixinClassName)) {
            return CompatibilityManager.travelersBackpackPresent();
        }
        if (PROBEJS_HTTP_CLIENT_MIXIN.equals(mixinClassName)) {
            return CompatibilityManager.probeJsPresent();
        }
        if (WATERMEDIA_HTTP_CLIENT_MIXIN.equals(mixinClassName)) {
            return CompatibilityManager.waterMediaYoutubePresent();
        }
        if (MODERNFIX_MODEL_EVENT_MIXIN.equals(mixinClassName)
                || MODERNFIX_RESOURCE_NODE_MIXIN.equals(mixinClassName)) {
            return CompatibilityManager.modernFixPresent();
        }
        if (VOXEL_SHAPE_CACHE_MIXIN.equals(mixinClassName)) {
            return !CompatibilityManager.lightspeedPresent();
        }
        if (CompatibilityManager.modernFixPresent()
                && (MODERNFIX_OVERLAPS.contains(mixinClassName) || DERIVED_MODEL_CACHES.contains(mixinClassName))) {
            return false;
        }
        if (CompatibilityManager.lightspeedPresent() && LIGHTSPEED_CORE_OVERLAPS.contains(mixinClassName)) {
            return false;
        }
        return !DERIVED_MODEL_CACHES.contains(mixinClassName)
                || (!CompatibilityManager.lightspeedPresent() && !CompatibilityManager.modelCompatibilityRiskPresent());
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
    }

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
    }
}

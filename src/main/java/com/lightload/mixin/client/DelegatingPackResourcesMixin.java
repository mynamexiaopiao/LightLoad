package com.lightload.mixin.client;

import com.lightload.cache.ResourceIndexCache;
import com.lightload.cache.ResourceRouteLayout;
import com.lightload.core.StartupCoordinator;
import com.lightload.core.ResourceLookupOptimizer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.pack.PackMetadataSection;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraftforge.resource.DelegatingPackResources;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.InputStream;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Bypasses repeated candidate-pack probing once a resource's winning mod is known. */
@Mixin(value = DelegatingPackResources.class, remap = false)
public abstract class DelegatingPackResourcesMixin {
    @Shadow @Final private List<PackResources> delegates;
    @Shadow @Final private Map<String, List<PackResources>> namespacesAssets;
    @Shadow @Final private Map<String, List<PackResources>> namespacesData;

    @Unique
    private Map<String, String> lightload$assetLayouts = Map.of();
    @Unique
    private Map<String, String> lightload$dataLayouts = Map.of();

    @Inject(method = "<init>", at = @At("RETURN"), require = 1, remap = false)
    private void lightload$prepareRouteLayouts(String packId, boolean builtin, PackMetadataSection metadata,
                                               List<? extends PackResources> packs, CallbackInfo ci) {
        ResourceIndexCache cache = StartupCoordinator.cache();
        if (cache == null && !ResourceLookupOptimizer.enabled()) return;
        Map<PackResources, String> identities = new IdentityHashMap<>();
        delegates.forEach(candidate -> ResourceRouteLayout.candidateIdentity(candidate)
                .ifPresent(identity -> identities.put(candidate, identity)));
        lightload$assetLayouts = lightload$buildLayouts(
                PackType.CLIENT_RESOURCES, namespacesAssets, identities);
        lightload$dataLayouts = lightload$buildLayouts(PackType.SERVER_DATA, namespacesData, identities);
        cache.recordRouteLayouts(lightload$assetLayouts.size() + lightload$dataLayouts.size());
    }

    @Inject(method = {"getResource", "m_214146_"}, at = @At("HEAD"), cancellable = true,
            require = 1, remap = false)
    private void lightload$findRoutedResource(PackType type, ResourceLocation location,
                                              CallbackInfoReturnable<IoSupplier<InputStream>> cir) {
        ResourceIndexCache cache = StartupCoordinator.cache();
        if (cache == null || !cache.routeCacheEnabled()) return;
        Map<String, List<PackResources>> candidatesByNamespace = type == PackType.CLIENT_RESOURCES
                ? namespacesAssets : namespacesData;
        Map<String, String> layouts = type == PackType.CLIENT_RESOURCES
                ? lightload$assetLayouts : lightload$dataLayouts;
        if (layouts == null) layouts = Map.of();
        List<PackResources> candidates = candidatesByNamespace.get(location.getNamespace());
        String layout = layouts.get(location.getNamespace());
        if (candidates == null) return;

        if (cache != null && cache.routeCacheEnabled() && layout != null) {
            int route = cache.findRoute(layout, type, location);
            if (route == ResourceIndexCache.MISSING_ROUTE) {
                cir.setReturnValue(null);
                return;
            }
            if (route >= 0 && route < candidates.size()) {
                IoSupplier<InputStream> resource = candidates.get(route).getResource(type, location);
                if (resource != null) {
                    cir.setReturnValue(resource);
                    return;
                }
                cache.invalidateRoute(layout, type, location);
            }
        }

        ResourceLookupOptimizer.LookupResult found = ResourceLookupOptimizer.findFirst(candidates, type, location);
        if (found != null) {
            if (cache != null && cache.routeCacheEnabled() && layout != null) {
                cache.recordRoute(layout, type, location, found.index());
            }
            cir.setReturnValue(found.resource());
            return;
        }
        if (cache != null && cache.routeCacheEnabled() && layout != null) {
            cache.recordRoute(layout, type, location, ResourceIndexCache.MISSING_ROUTE);
        }
        cir.setReturnValue(null);
    }

    @Unique
    private static Map<String, String> lightload$buildLayouts(
            PackType type, Map<String, List<PackResources>> candidatesByNamespace,
            Map<PackResources, String> identities) {
        Map<String, String> layouts = new LinkedHashMap<>();
        candidatesByNamespace.forEach((namespace, candidates) -> ResourceRouteLayout
                .create(type, namespace, candidates, identities)
                .ifPresent(layout -> layouts.put(namespace, layout)));
        return Map.copyOf(layouts);
    }

}

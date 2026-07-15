package com.lightload.mixin.client;

import com.lightload.core.ResourceLookupOptimizer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.resources.FallbackResourceManager;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceMetadata;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Parallelizes only uninterrupted, filter-free runs of known immutable packs. */
@Mixin(FallbackResourceManager.class)
public abstract class FallbackResourceManagerMixin {
    @Shadow @Final private static Logger LOGGER;
    @Shadow @Final public List<?> fallbacks;
    @Shadow @Final private PackType type;

    @Invoker("createStackMetadataFinder")
    protected abstract IoSupplier<ResourceMetadata> lightload$metadata(ResourceLocation location, int index);

    @Invoker("createResource")
    private static Resource lightload$createResource(PackResources pack, ResourceLocation location,
                                                     IoSupplier<InputStream> resource,
                                                     IoSupplier<ResourceMetadata> metadata) {
        throw new AssertionError();
    }

    @Inject(method = "getResource", at = @At("HEAD"), cancellable = true, require = 0)
    private void lightload$findResource(ResourceLocation location,
                                        CallbackInfoReturnable<Optional<Resource>> cir) {
        if (!ResourceLookupOptimizer.enabled() || fallbacks.size() <= 1) return;

        List<IndexedPack> segment = new ArrayList<>();
        for (int index = fallbacks.size() - 1; index >= 0; index--) {
            FallbackResourceManagerPackEntryAccessor entry =
                    (FallbackResourceManagerPackEntryAccessor) fallbacks.get(index);
            PackResources pack = entry.lightload$resources();
            if (pack != null && entry.lightload$filter() == null && ResourceLookupOptimizer.isSafe(pack)) {
                segment.add(new IndexedPack(index, pack));
                continue;
            }

            Optional<Resource> found = lightload$searchSegment(segment, location);
            if (found != null) {
                cir.setReturnValue(found);
                return;
            }
            segment.clear();

            if (pack != null) {
                IoSupplier<InputStream> resource = pack.getResource(type, location);
                if (resource != null) {
                    cir.setReturnValue(Optional.of(lightload$resource(pack, location, resource, index)));
                    return;
                }
            }
            if (entry.lightload$isFiltered(location)) {
                LOGGER.warn("Resource {} not found, but was filtered by pack {}", location, entry.lightload$name());
                cir.setReturnValue(Optional.empty());
                return;
            }
        }

        Optional<Resource> found = lightload$searchSegment(segment, location);
        cir.setReturnValue(found == null ? Optional.empty() : found);
    }

    @Unique
    private Optional<Resource> lightload$searchSegment(List<IndexedPack> segment, ResourceLocation location) {
        if (segment.isEmpty()) return null;
        List<PackResources> packs = segment.stream().map(IndexedPack::pack).toList();
        ResourceLookupOptimizer.LookupResult found = ResourceLookupOptimizer.findFirst(packs, type, location);
        if (found == null) return null;
        IndexedPack winner = segment.get(found.index());
        return Optional.of(lightload$resource(winner.pack(), location, found.resource(), winner.index()));
    }

    @Unique
    private Resource lightload$resource(PackResources pack, ResourceLocation location,
                                        IoSupplier<InputStream> resource, int index) {
        return lightload$createResource(pack, location, resource, lightload$metadata(location, index));
    }

    @Unique
    private record IndexedPack(int index, PackResources pack) {
    }
}

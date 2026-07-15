package com.lightload.mixin.client;

import com.lightload.core.ModFileBackedResourcePack;
import com.lightload.core.StartupCoordinator;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.repository.Pack;
import net.neoforged.neoforge.resource.ResourcePackLoader;
import net.neoforged.neoforgespi.language.IModFileInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = ResourcePackLoader.class, remap = false)
public abstract class ResourcePackLoaderMixin {
    @Inject(method = "createPackForMod", at = @At("RETURN"), cancellable = true,
            require = 0, remap = false)
    private static void lightload$bindModFile(IModFileInfo modFileInfo,
                                              CallbackInfoReturnable<Pack.ResourcesSupplier> cir) {
        if (!StartupCoordinator.optimizationsEnabled()) return;
        Pack.ResourcesSupplier original = cir.getReturnValue();
        if (original != null) cir.setReturnValue(new BoundPackResourcesSupplier(original, modFileInfo));
    }

    private record BoundPackResourcesSupplier(Pack.ResourcesSupplier delegate, IModFileInfo modFileInfo)
            implements Pack.ResourcesSupplier {
        @Override
        public PackResources openPrimary(PackLocationInfo location) {
            return bind(delegate.openPrimary(location));
        }

        @Override
        public PackResources openFull(PackLocationInfo location, Pack.Metadata metadata) {
            return bind(delegate.openFull(location, metadata));
        }

        private PackResources bind(PackResources resources) {
            if (resources instanceof ModFileBackedResourcePack pack) {
                pack.lightload$bindModFile(modFileInfo.getFile());
                pack.lightload$startCachePreload();
            }
            return resources;
        }
    }
}

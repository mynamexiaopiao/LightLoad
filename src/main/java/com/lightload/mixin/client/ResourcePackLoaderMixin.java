package com.lightload.mixin.client;

import com.lightload.core.ModFileBackedResourcePack;
import com.lightload.core.StartupCoordinator;
import net.minecraftforge.forgespi.language.IModFileInfo;
import net.minecraftforge.resource.PathPackResources;
import net.minecraftforge.resource.ResourcePackLoader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = ResourcePackLoader.class, remap = false)
public abstract class ResourcePackLoaderMixin {
    @Inject(method = "createPackForMod", at = @At("RETURN"), require = 0)
    private static void lightload$bindModFile(IModFileInfo modFileInfo,
                                              CallbackInfoReturnable<PathPackResources> cir) {
        if (!StartupCoordinator.optimizationsEnabled()) return;
        if (cir.getReturnValue() instanceof ModFileBackedResourcePack pack) {
            pack.lightload$bindModFile(modFileInfo.getFile());
            pack.lightload$startCachePreload();
        }
    }
}

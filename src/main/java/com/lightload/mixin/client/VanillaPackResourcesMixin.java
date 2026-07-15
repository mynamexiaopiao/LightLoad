package com.lightload.mixin.client;

import com.lightload.cache.ResourceIndexCache;
import com.lightload.core.StartupCoordinator;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.VanillaPackResources;
import net.minecraft.server.packs.resources.IoSupplier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.io.InputStream;

@Mixin(VanillaPackResources.class)
public abstract class VanillaPackResourcesMixin {
    @Inject(method = "getResource", at = @At("HEAD"), cancellable = true, require = 0)
    private void lightload$skipKnownMissing(PackType type, ResourceLocation location,
                                            CallbackInfoReturnable<IoSupplier<InputStream>> cir) {
        ResourceIndexCache cache = StartupCoordinator.cache();
        if (StartupCoordinator.optimizationsActive() && cache != null
                && Boolean.FALSE.equals(cache.findVanillaExistence(type, location))) {
            cir.setReturnValue(null);
        }
    }

    @Inject(method = "getResource", at = @At("RETURN"), require = 0)
    private void lightload$recordVanillaExistence(PackType type, ResourceLocation location,
                                                  CallbackInfoReturnable<IoSupplier<InputStream>> cir) {
        ResourceIndexCache cache = StartupCoordinator.cache();
        if (StartupCoordinator.optimizationsActive() && cache != null) {
            cache.recordVanillaExistence(type, location, cir.getReturnValue() != null);
        }
    }
}

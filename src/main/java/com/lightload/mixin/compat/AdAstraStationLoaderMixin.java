package com.lightload.mixin.compat;

import com.lightload.core.StartupNetworkGuard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "earth.terrarium.adastra.common.utils.radio.StationLoader", remap = false)
public abstract class AdAstraStationLoaderMixin {
    @Inject(method = "init", at = @At("HEAD"), cancellable = true, require = 0)
    private static void lightload$deferOnlineStations(CallbackInfo ci) {
        if (StartupNetworkGuard.deferOptionalRemoteAction(
                "Ad Astra station list",
                "earth.terrarium.adastra.common.utils.radio.StationLoader",
                "init")) {
            ci.cancel();
        }
    }
}

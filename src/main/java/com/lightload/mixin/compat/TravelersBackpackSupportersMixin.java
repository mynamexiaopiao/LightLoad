package com.lightload.mixin.compat;

import com.lightload.core.StartupNetworkGuard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "com.tiviacz.travelersbackpack.util.Supporters", remap = false)
public abstract class TravelersBackpackSupportersMixin {
    @Inject(method = "fetchSupporters", at = @At("HEAD"), cancellable = true, require = 0)
    private static void lightload$deferSupporters(CallbackInfo ci) {
        if (StartupNetworkGuard.deferOptionalRemoteAction(
                "Traveler's Backpack supporter list",
                "com.tiviacz.travelersbackpack.util.Supporters",
                "fetchSupporters")) {
            ci.cancel();
        }
    }
}

package com.lightload.mixin.compat;

import com.lightload.compat.LightspeedInterop;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "com.ccr4ft3r.lightspeed.Main", remap = false)
public abstract class LightspeedMainMixin {
    @Inject(method = "updateCacheFlags", at = @At("RETURN"), require = 0)
    private void lightload$applyModernFixInterop(CallbackInfo ci) {
        LightspeedInterop.applyModernFixMode();
    }
}

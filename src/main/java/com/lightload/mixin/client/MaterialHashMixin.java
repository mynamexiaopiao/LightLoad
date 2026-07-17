package com.lightload.mixin.client;

import net.minecraft.client.resources.model.Material;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Material.class)
public abstract class MaterialHashMixin {
    @Unique private int lightload$cachedHash;
    @Unique private volatile boolean lightload$hashCached;
    @Inject(method = "hashCode", at = @At("HEAD"), cancellable = true, require = 0)
    private void lightload$useCachedHash(CallbackInfoReturnable<Integer> cir) { if (lightload$hashCached) cir.setReturnValue(lightload$cachedHash); }
    @Inject(method = "hashCode", at = @At("RETURN"), require = 0)
    private void lightload$cacheHash(CallbackInfoReturnable<Integer> cir) { if (!lightload$hashCached) { lightload$cachedHash = cir.getReturnValueI(); lightload$hashCached = true; } }
}

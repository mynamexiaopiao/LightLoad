package com.lightload.mixin.compat;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Map;

@Pseudo
@Mixin(targets = "org.embeddedt.modernfix.resources.PackResourcesCacheEngine$Node", remap = false)
public abstract class ModernFixResourceNodeMixin {
    @Redirect(
            method = "optimize",
            at = @At(value = "INVOKE", target = "Ljava/util/Map;copyOf(Ljava/util/Map;)Ljava/util/Map;")
    )
    private Map<String, Object> lightload$keepConstantTimeLookup(Map<String, Object> children) {
        Object2ObjectOpenHashMap<String, Object> optimized = new Object2ObjectOpenHashMap<>(children);
        optimized.trim();
        return optimized;
    }
}

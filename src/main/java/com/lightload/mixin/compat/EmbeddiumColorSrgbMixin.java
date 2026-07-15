package com.lightload.mixin.compat;

import com.lightload.core.SrgbConversionCache;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;

@Pseudo
@Mixin(targets = "me.jellysquid.mods.sodium.client.util.color.ColorSRGB", remap = false)
public abstract class EmbeddiumColorSrgbMixin {
    @Shadow @Final private static int[] TO_SRGB8_TABLE;
    @Shadow @Final private static float MIN_BOUND;
    @Shadow @Final private static float MAX_BOUND;

    /**
     * @author Lightload
     * @reason Reuse exact conversions through an atomic shared cache without ThreadLocal lookup overhead.
     */
    @Overwrite
    private static int linearToSrgb(float value) {
        return SrgbConversionCache.convert(value, TO_SRGB8_TABLE, MIN_BOUND, MAX_BOUND);
    }
}

package com.lightload.mixin.client;

import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

@Mixin(ResourceLocation.class)
public abstract class ResourceLocationHashMixin {
    @Shadow @Final private String namespace;
    @Shadow @Final private String path;

    @Unique
    private int lightload$hash = Integer.MIN_VALUE;

    /**
     * Resource locations are immutable. Replacing callback injections here avoids allocating and boxing a
     * CallbackInfoReturnable on every map lookup, which is substantially more expensive than the hash itself.
     *
     * @author Lightload
     * @reason Cache the immutable hash without per-call callback allocation.
     */
    @Overwrite
    public int hashCode() {
        int cached = lightload$hash;
        if (cached != Integer.MIN_VALUE) return cached;
        int computed = 31 * namespace.hashCode() + path.hashCode();
        lightload$hash = computed;
        return computed;
    }
}

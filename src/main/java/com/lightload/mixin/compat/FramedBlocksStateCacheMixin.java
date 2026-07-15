package com.lightload.mixin.compat;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Collection;
import java.util.stream.Stream;

@Pseudo
@Mixin(targets = "xfacthd.framedblocks.common.data.StateCacheBuilder", remap = false)
public abstract class FramedBlocksStateCacheMixin {
    @Redirect(
            method = "initializeStateCaches",
            at = @At(value = "INVOKE", target = "Ljava/util/Collection;stream()Ljava/util/stream/Stream;")
    )
    private static Stream<?> lightload$parallelizeIndependentBlockCaches(Collection<?> blocks) {
        return blocks.size() >= 64 ? blocks.parallelStream() : blocks.stream();
    }
}

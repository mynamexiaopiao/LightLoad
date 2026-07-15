package com.lightload.mixin.client;

import com.lightload.core.LazyBlockStateCache;
import net.minecraft.core.Registry;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.neoforged.neoforge.registries.NeoForgeRegistryCallbacks$BlockCallbacks", remap = false)
public abstract class GameDataBlockCallbacksMixin {
    @Redirect(
            method = "onBake",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/state/BlockBehaviour$BlockStateBase;initCache()V", remap = true),
            remap = false
    )
    private void lightload$deferBlockStateCache(BlockState state) {
        if (!LazyBlockStateCache.enabled()) state.initCache();
    }

    @Inject(method = "onBake", at = @At("TAIL"), remap = false)
    private void lightload$invalidateBlockStateCaches(Registry<Block> registry, CallbackInfo ci) {
        if (LazyBlockStateCache.enabled()) LazyBlockStateCache.invalidate(registry);
    }
}

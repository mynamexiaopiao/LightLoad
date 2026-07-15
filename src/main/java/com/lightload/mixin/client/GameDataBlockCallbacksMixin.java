package com.lightload.mixin.client;

import com.lightload.core.LazyBlockStateCache;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.IForgeRegistryInternal;
import net.minecraftforge.registries.RegistryManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net/minecraftforge/registries/GameData$BlockCallbacks")
public abstract class GameDataBlockCallbacksMixin {
    @Redirect(
            method = "onBake",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/state/BlockState;initCache()V")
    )
    private void lightload$deferBlockStateCache(BlockState state) {
        if (!LazyBlockStateCache.enabled()) state.initCache();
    }

    @Inject(method = "onBake", at = @At("TAIL"), remap = false)
    private void lightload$invalidateBlockStateCaches(IForgeRegistryInternal<Block> owner, RegistryManager stage,
                                                       CallbackInfo ci) {
        if (LazyBlockStateCache.enabled()) LazyBlockStateCache.invalidate(owner);
    }
}

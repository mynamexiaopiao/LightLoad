package com.lightload.mixin.client;

import com.google.common.collect.ImmutableMap;
import com.lightload.core.BlockStateCacheAccess;
import com.lightload.core.LazyBlockStateCache;
import com.mojang.serialization.MapCodec;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateHolder;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class BlockStateBaseCacheMixin extends StateHolder<Block, BlockState> implements BlockStateCacheAccess {
    @Unique private static final FluidState LIGHTLOAD$EMPTY_FLUID = Fluids.EMPTY.defaultFluidState();
    @Unique private static boolean lightload$buildingCache;
    @Unique private volatile boolean lightload$cacheInvalid;
    @Shadow private BlockBehaviour.BlockStateBase.Cache cache;
    @Shadow private FluidState fluidState;
    @Shadow private boolean isRandomlyTicking;
    @Shadow @Deprecated private boolean legacySolid;

    protected BlockStateBaseCacheMixin(Block owner, ImmutableMap<Property<?>, Comparable<?>> values, MapCodec<BlockState> codec) { super(owner, values, codec); }
    @Shadow public abstract void initCache();
    @Shadow protected abstract BlockState asState();
    @Override public void lightload$invalidateCache() { lightload$cacheInvalid = true; }

    @Unique private void lightload$generateCache() {
        if (!lightload$cacheInvalid) return;
        synchronized (BlockBehaviour.BlockStateBase.class) {
            if (!lightload$cacheInvalid || lightload$buildingCache) return;
            lightload$buildingCache = true;
            try { initCache(); lightload$cacheInvalid = false; LazyBlockStateCache.cacheGenerated(); }
            finally { lightload$buildingCache = false; }
        }
    }

    @Redirect(method = "*", at = @At(value = "FIELD", opcode = Opcodes.GETFIELD, target = "Lnet/minecraft/world/level/block/state/BlockBehaviour$BlockStateBase;cache:Lnet/minecraft/world/level/block/state/BlockBehaviour$BlockStateBase$Cache;", ordinal = 0))
    private BlockBehaviour.BlockStateBase.Cache lightload$cacheOnDemand(BlockBehaviour.BlockStateBase ignored) { lightload$generateCache(); return cache; }

    @Redirect(method = "*", at = @At(value = "FIELD", opcode = Opcodes.GETFIELD, target = "Lnet/minecraft/world/level/block/state/BlockBehaviour$BlockStateBase;fluidState:Lnet/minecraft/world/level/material/FluidState;", ordinal = 0), require = 0)
    private FluidState lightload$fluidStateOnDemand(BlockBehaviour.BlockStateBase ignored) {
        if (lightload$cacheInvalid && fluidState == LIGHTLOAD$EMPTY_FLUID) synchronized (BlockBehaviour.BlockStateBase.class) {
            if (!lightload$buildingCache) {
                lightload$buildingCache = true;
                try { fluidState = owner.getFluidState(asState()); } finally { lightload$buildingCache = false; }
            }
        }
        return fluidState;
    }

    @Redirect(method = "*", at = @At(value = "FIELD", opcode = Opcodes.GETFIELD, target = "Lnet/minecraft/world/level/block/state/BlockBehaviour$BlockStateBase;isRandomlyTicking:Z", ordinal = 0))
    private boolean lightload$randomTickingOnDemand(BlockBehaviour.BlockStateBase ignored) { return lightload$cacheInvalid ? owner.isRandomlyTicking(asState()) : isRandomlyTicking; }

    @Redirect(method = "*", at = @At(value = "FIELD", opcode = Opcodes.GETFIELD, target = "Lnet/minecraft/world/level/block/state/BlockBehaviour$BlockStateBase;legacySolid:Z", ordinal = 0))
    private boolean lightload$legacySolidOnDemand(BlockBehaviour.BlockStateBase ignored) { lightload$generateCache(); return legacySolid; }
}

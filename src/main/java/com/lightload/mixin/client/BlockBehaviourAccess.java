package com.lightload.mixin.client;

import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(BlockBehaviour.class)
public interface BlockBehaviourAccess {
    @Invoker("getFluidState")
    FluidState lightload$invokeGetFluidState(BlockState state);

    @Invoker("isRandomlyTicking")
    boolean lightload$invokeIsRandomlyTicking(BlockState state);
}

package com.lightload.mixin.client;

import net.minecraft.client.renderer.block.model.multipart.Selector;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.AbstractMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

@Mixin(Selector.class)
public abstract class SelectorPredicateMixin {
    @Unique private volatile Map.Entry<StateDefinition<Block, BlockState>, Predicate<BlockState>> lightload$firstPredicate;
    @Unique private volatile Map<StateDefinition<Block, BlockState>, Predicate<BlockState>> lightload$additionalPredicates;
    @Inject(method = "getPredicate", at = @At("HEAD"), cancellable = true, require = 0)
    private void lightload$findPredicate(StateDefinition<Block, BlockState> definition, CallbackInfoReturnable<Predicate<BlockState>> cir) { Map.Entry<StateDefinition<Block, BlockState>, Predicate<BlockState>> first = lightload$firstPredicate; Predicate<BlockState> result = first != null && first.getKey() == definition ? first.getValue() : null; Map<StateDefinition<Block, BlockState>, Predicate<BlockState>> values = lightload$additionalPredicates; if (result == null && values != null) result = values.get(definition); if (result != null) cir.setReturnValue(result); }
    @Inject(method = "getPredicate", at = @At("RETURN"), require = 0)
    private void lightload$rememberPredicate(StateDefinition<Block, BlockState> definition, CallbackInfoReturnable<Predicate<BlockState>> cir) { Predicate<BlockState> result = cir.getReturnValue(); if (result == null) return; Map.Entry<StateDefinition<Block, BlockState>, Predicate<BlockState>> first = lightload$firstPredicate; if (first == null) synchronized (this) { if (lightload$firstPredicate == null) { lightload$firstPredicate = new AbstractMap.SimpleImmutableEntry<>(definition, result); return; } first = lightload$firstPredicate; } if (first.getKey() != definition) lightload$additionalCache().putIfAbsent(definition, result); }
    @Unique private Map<StateDefinition<Block, BlockState>, Predicate<BlockState>> lightload$additionalCache() { Map<StateDefinition<Block, BlockState>, Predicate<BlockState>> values = lightload$additionalPredicates; if (values != null) return values; synchronized (this) { if (lightload$additionalPredicates == null) lightload$additionalPredicates = new ConcurrentHashMap<>(); return lightload$additionalPredicates; } }
}

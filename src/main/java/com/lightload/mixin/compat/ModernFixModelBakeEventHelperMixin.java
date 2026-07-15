package com.lightload.mixin.compat;

import com.google.common.base.Predicate;
import com.lightload.compat.LazyModelLocationSet;
import com.lightload.compat.ModelVisibilityIndex;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.DefaultedRegistry;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map.Entry;
import java.util.Set;
import java.util.function.Consumer;

@Pseudo
@Mixin(targets = "org.embeddedt.modernfix.forge.dynresources.ModelBakeEventHelper", remap = false)
public abstract class ModernFixModelBakeEventHelperMixin {
    @Shadow
    @Final
    @Mutable
    private static Map<String, Object> MOD_VISIBILITY_CONFIGURATION;

    @Shadow
    @Final
    @Mutable
    private Set<ResourceLocation> topLevelModelLocations;

    @Shadow
    @Final
    private Set<String> namespacesWithModels;

    @Inject(method = "<clinit>", at = @At("TAIL"))
    private static void lightload$restrictNamespaceLocalScanners(CallbackInfo ci) {
        MOD_VISIBILITY_CONFIGURATION = ModelVisibilityIndex.restrictKnownScanners(MOD_VISIBILITY_CONFIGURATION);
    }

    @Inject(method = "<init>", at = @At("TAIL"))
    private void lightload$buildNamespaceIndex(Map<ResourceLocation, BakedModel> modelRegistry, CallbackInfo ci) {
        topLevelModelLocations = new LazyModelLocationSet(topLevelModelLocations);
    }

    @Redirect(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/core/DefaultedRegistry;iterator()Ljava/util/Iterator;"
            )
    )
    private Iterator<Block> lightload$skipEagerBlockStateCount(DefaultedRegistry<Block> ignored) {
        return Collections.emptyIterator();
    }

    @Redirect(
            method = "<init>",
            at = @At(value = "INVOKE", target = "Ljava/util/Set;forEach(Ljava/util/function/Consumer;)V", ordinal = 0)
    )
    @SuppressWarnings("unchecked")
    private void lightload$collectBlockNamespaces(Set<?> entries, Consumer<?> ignored) {
        for (Object value : entries) {
            Entry<ResourceKey<Block>, Block> entry = (Entry<ResourceKey<Block>, Block>) value;
            namespacesWithModels.add(entry.getKey().location().getNamespace());
        }
    }

    @Redirect(
            method = "<init>",
            at = @At(value = "INVOKE", target = "Ljava/util/Set;forEach(Ljava/util/function/Consumer;)V", ordinal = 1)
    )
    @SuppressWarnings("unchecked")
    private void lightload$collectItemNamespaces(Set<?> keys, Consumer<?> ignored) {
        for (Object value : keys) {
            ResourceLocation key = (ResourceLocation) value;
            namespacesWithModels.add(key.getNamespace());
        }
    }

    @Redirect(
            method = "wrapRegistry",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/google/common/collect/Sets;filter(Ljava/util/Set;Lcom/google/common/base/Predicate;)Ljava/util/Set;"
            )
    )
    private Set<ResourceLocation> lightload$selectIndexedNamespaces(Set<ResourceLocation> ignored,
                                                                    Predicate<? super ResourceLocation> predicate) {
        if (topLevelModelLocations instanceof LazyModelLocationSet locations) {
            return locations.select(predicate::apply);
        }
        return ModelVisibilityIndex.select(ModelVisibilityIndex.build(topLevelModelLocations), predicate::apply);
    }
}

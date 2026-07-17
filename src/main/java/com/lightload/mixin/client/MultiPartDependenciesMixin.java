package com.lightload.mixin.client;

import net.minecraft.client.renderer.block.model.multipart.MultiPart;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import java.util.Collection;
import java.util.Set;

@Mixin(MultiPart.class)
public abstract class MultiPartDependenciesMixin {
    @Unique private volatile Collection<ResourceLocation> lightload$dependencies;
    @Inject(method = "getDependencies", at = @At("HEAD"), cancellable = true, require = 0)
    private void lightload$findDependencies(CallbackInfoReturnable<Collection<ResourceLocation>> cir) { Collection<ResourceLocation> values = lightload$dependencies; if (values != null) cir.setReturnValue(values); }
    @Inject(method = "getDependencies", at = @At("RETURN"), require = 0)
    private void lightload$rememberDependencies(CallbackInfoReturnable<Collection<ResourceLocation>> cir) { if (lightload$dependencies == null && cir.getReturnValue() != null) lightload$dependencies = Set.copyOf(cir.getReturnValue()); }
}

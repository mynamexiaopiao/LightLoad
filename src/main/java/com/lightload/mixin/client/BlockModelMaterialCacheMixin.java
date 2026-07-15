package com.lightload.mixin.client;

import net.minecraft.client.renderer.block.model.BlockModel;
import net.minecraft.client.resources.model.Material;
import net.minecraft.client.resources.model.UnbakedModel;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

@Mixin(BlockModel.class)
public abstract class BlockModelMaterialCacheMixin {
    @Unique
    private volatile Map<String, Material> lightload$materials;

    @Inject(method = "getMaterial", at = @At("HEAD"), cancellable = true, require = 0)
    private void lightload$findMaterial(String name, CallbackInfoReturnable<Material> cir) {
        Map<String, Material> materials = lightload$materials;
        if (materials == null) return;
        Material material = materials.get(name);
        if (material != null) cir.setReturnValue(material);
    }

    @Inject(method = "getMaterial", at = @At("RETURN"), require = 0)
    private void lightload$rememberMaterial(String name, CallbackInfoReturnable<Material> cir) {
        Material material = cir.getReturnValue();
        if (material != null) lightload$materialCache().putIfAbsent(name, material);
    }

    @Inject(method = "resolveParents", at = @At("HEAD"), require = 0)
    private void lightload$invalidateMaterials(Function<ResourceLocation, UnbakedModel> modelGetter, CallbackInfo ci) {
        Map<String, Material> materials = lightload$materials;
        if (materials != null) materials.clear();
    }

    @Unique
    private Map<String, Material> lightload$materialCache() {
        Map<String, Material> materials = lightload$materials;
        if (materials != null) return materials;
        synchronized (this) {
            if (lightload$materials == null) lightload$materials = new ConcurrentHashMap<>();
            return lightload$materials;
        }
    }
}

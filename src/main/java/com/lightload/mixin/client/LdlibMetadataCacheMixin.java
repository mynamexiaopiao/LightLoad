package com.lightload.mixin.client;

import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Pseudo
@Mixin(targets = "com.lowdragmc.lowdraglib.client.model.custommodel.LDLMetadataSection", remap = false)
public abstract class LdlibMetadataCacheMixin {
    @Shadow
    @Final
    private static Map<ResourceLocation, Object> METADATA_CACHE;

    @Unique
    private static final Map<ResourceLocation, ResourceLocation> LIGHTLOAD$ABSOLUTE_TEXTURES =
            new ConcurrentHashMap<>();

    @Inject(
            method = "spriteToAbsolute(Lnet/minecraft/resources/ResourceLocation;)Lnet/minecraft/resources/ResourceLocation;",
            at = @At("HEAD"),
            cancellable = true
    )
    private static void lightload$reuseAbsoluteTexture(ResourceLocation texture,
                                                        CallbackInfoReturnable<ResourceLocation> cir) {
        ResourceLocation cached = LIGHTLOAD$ABSOLUTE_TEXTURES.get(texture);
        if (cached != null) cir.setReturnValue(cached);
    }

    @Inject(
            method = "spriteToAbsolute(Lnet/minecraft/resources/ResourceLocation;)Lnet/minecraft/resources/ResourceLocation;",
            at = @At("RETURN")
    )
    private static void lightload$rememberAbsoluteTexture(ResourceLocation texture,
                                                           CallbackInfoReturnable<ResourceLocation> cir) {
        LIGHTLOAD$ABSOLUTE_TEXTURES.putIfAbsent(texture, cir.getReturnValue());
    }

    @Inject(
            method = "getMetadata(Lnet/minecraft/resources/ResourceLocation;)Lcom/lowdragmc/lowdraglib/client/model/custommodel/LDLMetadataSection;",
            at = @At("HEAD"),
            cancellable = true
    )
    private static void lightload$singleMetadataLookup(ResourceLocation texture,
                                                        CallbackInfoReturnable<Object> cir) {
        Object cached = METADATA_CACHE.get(texture);
        if (cached != null) cir.setReturnValue(cached);
    }

    @Inject(method = "clearCache", at = @At("RETURN"))
    private static void lightload$clearAbsoluteTextures(CallbackInfo ci) {
        LIGHTLOAD$ABSOLUTE_TEXTURES.clear();
    }
}

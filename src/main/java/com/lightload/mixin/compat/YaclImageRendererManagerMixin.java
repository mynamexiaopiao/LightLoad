package com.lightload.mixin.compat;

import com.lightload.LightloadMod;
import com.lightload.core.StartupCoordinator;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Pseudo
@Mixin(targets = "dev.isxander.yacl3.gui.image.ImageRendererManager", remap = false)
public abstract class YaclImageRendererManagerMixin {
    @Unique private static final Set<ResourceLocation> LIGHTLOAD$REQUESTED = ConcurrentHashMap.newKeySet();

    @Inject(method = "getImage", at = @At("HEAD"), require = 0)
    private static void lightload$lazyLoadImage(ResourceLocation location,
                                                 CallbackInfoReturnable<Optional<?>> cir) {
        if (!StartupCoordinator.startupIsComplete() || !StartupCoordinator.lazyYaclImagesEnabled()
                || !LIGHTLOAD$REQUESTED.add(location)) return;
        String path = location.getPath();
        String factoryMethod = path.endsWith(".webp") ? "createWEBPFromTexture"
                : path.endsWith(".gif") ? "createGIFFromTexture" : null;
        if (factoryMethod == null) return;
        try {
            ClassLoader loader = YaclImageRendererManagerMixin.class.getClassLoader();
            Class<?> animated = Class.forName(
                    "dev.isxander.yacl3.gui.image.impl.AnimatedDynamicTextureImage", false, loader);
            Object factory = animated.getMethod(factoryMethod, ResourceLocation.class).invoke(null, location);
            Class<?> factoryType = Class.forName(
                    "dev.isxander.yacl3.gui.image.ImageRendererFactory", false, loader);
            Class<?> manager = Class.forName(
                    "dev.isxander.yacl3.gui.image.ImageRendererManager", false, loader);
            Method register = manager.getMethod("registerImage", ResourceLocation.class, factoryType);
            register.invoke(null, location, factory);
        } catch (ReflectiveOperationException exception) {
            LightloadMod.LOGGER.warn("[Lightload] Could not lazily load YACL image {}", location, exception);
        }
    }
}

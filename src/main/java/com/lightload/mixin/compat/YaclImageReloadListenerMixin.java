package com.lightload.mixin.compat;

import com.lightload.core.StartupCoordinator;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Pseudo
@Mixin(targets = "dev.isxander.yacl3.gui.image.YACLImageReloadListener", remap = false)
public abstract class YaclImageReloadListenerMixin {
    @Inject(method = "prepare", at = @At("HEAD"), cancellable = true, require = 0)
    private void lightload$deferAnimatedImages(ResourceManager resourceManager, Executor executor,
                                                CallbackInfoReturnable<CompletableFuture<?>> cir) {
        if (StartupCoordinator.shouldDeferYaclImagePreload()) {
            cir.setReturnValue(CompletableFuture.completedFuture(List.of()));
        }
    }
}

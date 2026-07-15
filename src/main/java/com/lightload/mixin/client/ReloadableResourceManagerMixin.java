package com.lightload.mixin.client;

import com.lightload.core.StartupCoordinator;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.resources.ReloadInstance;
import net.minecraft.util.Unit;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

@Mixin(net.minecraft.server.packs.resources.ReloadableResourceManager.class)
public abstract class ReloadableResourceManagerMixin {
    @Inject(method = "createReload", at = @At("HEAD"), require = 0)
    private void lightload$reloadStarted(Executor backgroundExecutor, Executor gameExecutor,
                                        CompletableFuture<Unit> waitingFor, List<PackResources> resourcePacks,
                                        CallbackInfoReturnable<ReloadInstance> cir) {
        StartupCoordinator.resourceReloadStarted();
    }

    @Inject(method = "createReload", at = @At("RETURN"), require = 0)
    private void lightload$trackReload(Executor backgroundExecutor, Executor gameExecutor,
                                      CompletableFuture<Unit> waitingFor, List<PackResources> resourcePacks,
                                      CallbackInfoReturnable<ReloadInstance> cir) {
        ReloadInstance reload = cir.getReturnValue();
        if (reload != null) reload.done().whenComplete((ignored, throwable) -> StartupCoordinator.resourceReloadFinished());
    }
}

package com.lightload.mixin.client;

import com.lightload.core.StartupCoordinator;
import net.neoforged.neoforge.client.loading.ClientModLoader;
import net.neoforged.fml.ModLoadingException;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = ClientModLoader.class, remap = false)
public abstract class ClientModLoaderMixin {
    @Shadow private static ModLoadingException error;

    @Inject(method = "completeModLoading", at = @At("RETURN"), require = 0)
    private static void lightload$startupCompleted(CallbackInfoReturnable<Runnable> cir) {
        if (error == null) StartupCoordinator.startupCompleted();
    }
}

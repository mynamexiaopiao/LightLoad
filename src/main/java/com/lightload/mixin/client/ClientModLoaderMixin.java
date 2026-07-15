package com.lightload.mixin.client;

import com.lightload.core.StartupCoordinator;
import net.minecraftforge.client.loading.ClientModLoader;
import net.minecraftforge.fml.LoadingFailedException;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = ClientModLoader.class, remap = false)
public abstract class ClientModLoaderMixin {
    @Shadow private static LoadingFailedException error;

    @Inject(method = "completeModLoading", at = @At("RETURN"), require = 0)
    private static void lightload$startupCompleted(CallbackInfoReturnable<Boolean> cir) {
        if (error == null) StartupCoordinator.startupCompleted();
    }
}

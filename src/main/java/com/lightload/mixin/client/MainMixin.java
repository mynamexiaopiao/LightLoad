package com.lightload.mixin.client;

import com.lightload.core.StartupCoordinator;
import net.minecraft.client.main.Main;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Main.class)
public abstract class MainMixin {
    @Inject(method = "main", at = @At("HEAD"), require = 0)
    private static void lightload$clientMainStarted(String[] arguments, CallbackInfo ci) {
        StartupCoordinator.minecraftConstructionStarted();
    }
}

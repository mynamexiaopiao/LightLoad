package com.lightload.mixin.client;

import com.lightload.core.StartupCoordinator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.main.GameConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
    @Inject(method = "<init>", at = @At("RETURN"), require = 0)
    private void lightload$afterMinecraftConstructed(GameConfig gameConfig, CallbackInfo ci) {
        StartupCoordinator.minecraftConstructionFinished();
    }

    @Inject(method = "setScreen", at = @At("RETURN"), require = 0)
    private void lightload$noticeStartupScreen(Screen screen, CallbackInfo ci) {
        if (screen != null) StartupCoordinator.startupScreenShown(screen.getClass().getName());
    }
}

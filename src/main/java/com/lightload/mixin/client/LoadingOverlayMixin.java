package com.lightload.mixin.client;

import com.lightload.core.StartupCoordinator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.LoadingOverlay;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LoadingOverlay.class)
public abstract class LoadingOverlayMixin {
    @Shadow @Final private Minecraft minecraft;

    @Inject(method = "render", at = @At("RETURN"), require = 0)
    private void lightload$drawLoadingTime(GuiGraphics graphics, int mouseX, int mouseY,
                                           float partialTick, CallbackInfo ci) {
        if (!StartupCoordinator.shouldShowLoadingTime()) return;
        int x = 8;
        int y = Math.max(8, graphics.guiHeight() - minecraft.font.lineHeight - 8);
        graphics.drawString(minecraft.font, StartupCoordinator.loadingTimeText(), x, y, 0xE0FFFFFF, true);
    }
}

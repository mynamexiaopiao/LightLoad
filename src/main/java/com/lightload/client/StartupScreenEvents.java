package com.lightload.client;

import com.lightload.LightloadMod;
import com.lightload.core.StartupCoordinator;
import net.minecraft.client.gui.screens.TitleScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;

@EventBusSubscriber(modid = LightloadMod.MOD_ID, value = Dist.CLIENT)
public final class StartupScreenEvents {
    private StartupScreenEvents() {
    }

    @SubscribeEvent
    public static void onScreenInit(ScreenEvent.Init.Post event) {
        if (event.getScreen() instanceof TitleScreen) StartupCoordinator.titleScreenInitialized();
    }
}

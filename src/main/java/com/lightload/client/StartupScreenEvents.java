package com.lightload.client;

import com.lightload.LightloadMod;
import com.lightload.core.StartupCoordinator;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = LightloadMod.MOD_ID, value = Dist.CLIENT)
public final class StartupScreenEvents {
    private StartupScreenEvents() {
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void onScreenInit(ScreenEvent.Init event) {
        if (event.getScreen() instanceof TitleScreen) StartupCoordinator.titleScreenInitialized();
    }
}

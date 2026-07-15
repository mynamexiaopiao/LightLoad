package com.lightload;

import com.mojang.logging.LogUtils;
import com.lightload.core.StartupCoordinator;
import com.lightload.config.LightloadConfig;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

@Mod(LightloadMod.MOD_ID)
public class LightloadMod {
    public static final String MOD_ID = "lightload";
    public static final Logger LOGGER = LogUtils.getLogger();

    public LightloadMod(IEventBus modEventBus, ModContainer modContainer) {
        modContainer.registerConfig(ModConfig.Type.CLIENT, LightloadConfig.SPEC, "lightload-client.toml");
        if (FMLEnvironment.dist.isClient()) {
            com.lightload.client.ClientConfigScreenRegistration.register(modContainer);
        }
        modEventBus.addListener(this::onConfigLoading);
    }

    private void onConfigLoading(ModConfigEvent.Loading event) {
        if (FMLEnvironment.dist.isClient() && event.getConfig().getSpec() == LightloadConfig.SPEC) {
            StartupCoordinator.initialize();
        }
    }
}

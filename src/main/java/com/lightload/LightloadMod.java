package com.lightload;

import com.mojang.logging.LogUtils;
import com.lightload.core.StartupCoordinator;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

@Mod(LightloadMod.MOD_ID)
public class LightloadMod {
    public static final String MOD_ID = "lightload";
    public static final Logger LOGGER = LogUtils.getLogger();

    public LightloadMod() {
        if (FMLEnvironment.dist.isClient()) {
            StartupCoordinator.initialize();
        }
    }
}

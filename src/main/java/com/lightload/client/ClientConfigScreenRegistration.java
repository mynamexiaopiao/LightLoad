package com.lightload.client;

import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/** Client-only bridge for NeoForge's generic ModConfig screen. */
public final class ClientConfigScreenRegistration {
    private ClientConfigScreenRegistration() {
    }

    public static void register(ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class,
                (minecraft, parent) -> new ConfigurationScreen(container, parent));
    }
}

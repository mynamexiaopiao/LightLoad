package com.lightload.bootstrap;

import net.neoforged.neoforgespi.ILaunchContext;
import net.neoforged.neoforgespi.locating.IDiscoveryPipeline;
import net.neoforged.neoforgespi.locating.IModFileCandidateLocator;
import net.neoforged.neoforgespi.locating.IncompatibleFileReporting;
import net.neoforged.neoforgespi.locating.ModFileDiscoveryAttributes;

import java.nio.file.Path;

/** NeoForge service locator which exposes the embedded GAME-layer payload. */
public final class LightloadEarlyModLocator implements IModFileCandidateLocator {
    @Override
    public void findCandidates(ILaunchContext context, IDiscoveryPipeline pipeline) {
        RuntimeAgentBootstrap.installEarly();
        try {
            Path gameDirectory = RuntimeAgentBootstrap.resolveGameDirectory();
            if (EmbeddedGamePayload.hasExternalGameJar(gameDirectory)) {
                System.setProperty("lightload.bundle.payload", "external");
                return;
            }
            Path payload = EmbeddedGamePayload.materialize(gameDirectory);
            System.setProperty("lightload.bundle.payload", "embedded");
            pipeline.addPath(payload, ModFileDiscoveryAttributes.DEFAULT.withLocator(this),
                    IncompatibleFileReporting.ERROR);
        } catch (Throwable throwable) {
            System.setProperty("lightload.bundle.payload", "failed");
            System.setProperty("lightload.bundle.payloadError", throwable.getClass().getName()
                    + (throwable.getMessage() == null ? "" : ": " + throwable.getMessage()));
            System.err.println("[Lightload] Embedded GAME payload unavailable: " + throwable);
        }
    }

    @Override
    public int getPriority() {
        return HIGHEST_SYSTEM_PRIORITY;
    }

    @Override
    public String toString() {
        return "lightload early bootstrap";
    }
}

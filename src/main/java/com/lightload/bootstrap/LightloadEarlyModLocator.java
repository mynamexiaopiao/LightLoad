package com.lightload.bootstrap;

import net.minecraftforge.fml.loading.moddiscovery.AbstractJarFileModLocator;

import java.nio.file.Path;
import java.util.Map;
import java.util.stream.Stream;

/** Early bootstrap service which exposes the embedded GAME-layer payload as a normal Forge mod. */
public final class LightloadEarlyModLocator extends AbstractJarFileModLocator {
    @Override
    public Stream<Path> scanCandidates() {
        try {
            Path gameDirectory = RuntimeAgentBootstrap.resolveGameDirectory();
            if (EmbeddedGamePayload.hasExternalGameJar(gameDirectory)) {
                System.setProperty("lightload.bundle.payload", "external");
                return Stream.empty();
            }
            Path payload = EmbeddedGamePayload.materialize(gameDirectory);
            System.setProperty("lightload.bundle.payload", "embedded");
            return Stream.of(payload);
        } catch (Throwable throwable) {
            System.setProperty("lightload.bundle.payload", "failed");
            System.setProperty("lightload.bundle.payloadError", throwable.getClass().getName()
                    + (throwable.getMessage() == null ? "" : ": " + throwable.getMessage()));
            System.err.println("[Lightload] Embedded GAME payload unavailable: " + throwable);
            return Stream.empty();
        }
    }

    @Override
    public String name() {
        return "lightload early bootstrap";
    }

    @Override
    public void initArguments(Map<String, ?> arguments) {
        RuntimeAgentBootstrap.installEarly();
    }
}

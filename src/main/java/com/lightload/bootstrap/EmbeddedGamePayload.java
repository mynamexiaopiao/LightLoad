package com.lightload.bootstrap;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.jar.JarFile;

/** Extracts the bundled GAME-layer mod to a stable, content-addressed cache path. */
final class EmbeddedGamePayload {
    static final String ENTRY = "META-INF/lightload/lightload-game.jar";
    private static final String FILE_PREFIX = "lightload-game-";
    private static final String FILE_SUFFIX = ".jar";

    private EmbeddedGamePayload() {
    }

    static Path materialize(Path gameDirectory) throws IOException {
        byte[] payload;
        ClassLoader loader = LightloadEarlyModLocator.class.getClassLoader();
        try (InputStream input = loader.getResourceAsStream(ENTRY)) {
            if (input == null) throw new IOException("Missing bundled entry " + ENTRY);
            payload = input.readAllBytes();
        }
        return materialize(gameDirectory, payload);
    }

    static Path materialize(Path gameDirectory, byte[] payload) throws IOException {
        if (payload.length < 4 || payload[0] != 'P' || payload[1] != 'K') {
            throw new IOException("Bundled GAME payload is not a JAR");
        }
        String digest = sha256(payload);
        Path directory = gameDirectory.resolve("cache").resolve("lightload").resolve("bundle");
        Path target = directory.resolve(FILE_PREFIX + digest + FILE_SUFFIX);
        Files.createDirectories(directory);
        if (!matches(target, payload.length, digest)) {
            Path temporary = directory.resolve(target.getFileName() + "."
                    + ProcessHandle.current().pid() + ".tmp");
            try {
                Files.write(temporary, payload);
                move(temporary, target);
            } finally {
                Files.deleteIfExists(temporary);
            }
            if (!matches(target, payload.length, digest)) {
                Files.deleteIfExists(target);
                throw new IOException("Extracted GAME payload failed verification");
            }
        }
        removeStalePayloads(directory, target);
        return target;
    }

    static boolean hasExternalGameJar(Path gameDirectory) {
        Path mods = gameDirectory.resolve("mods");
        if (!Files.isDirectory(mods)) return false;
        Path ownJar = codeSource();
        try (var files = Files.list(mods)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> ownJar == null || !sameFile(path, ownJar))
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar"))
                    .anyMatch(EmbeddedGamePayload::isLightloadGameJar);
        } catch (IOException ignored) {
            return false;
        }
    }

    private static boolean isLightloadGameJar(Path path) {
        try (JarFile jar = new JarFile(path.toFile(), false)) {
            var entry = jar.getJarEntry("META-INF/mods.toml");
            if (entry == null) return false;
            try (InputStream input = jar.getInputStream(entry)) {
                String toml = new String(input.readAllBytes(), StandardCharsets.UTF_8);
                return toml.matches("(?s).*\\bmodId\\s*=\\s*[\"']lightload[\"'].*");
            }
        } catch (IOException | RuntimeException ignored) {
            return false;
        }
    }

    private static Path codeSource() {
        try {
            URI location = LightloadEarlyModLocator.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            return Path.of(location).toAbsolutePath().normalize();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean sameFile(Path first, Path second) {
        try {
            return Files.isSameFile(first, second);
        } catch (IOException ignored) {
            return first.toAbsolutePath().normalize().equals(second);
        }
    }

    private static boolean matches(Path path, int expectedLength, String expectedDigest) {
        try {
            return Files.isRegularFile(path) && Files.size(path) == expectedLength
                    && expectedDigest.equals(sha256(Files.readAllBytes(path)));
        } catch (IOException ignored) {
            return false;
        }
    }

    private static void removeStalePayloads(Path directory, Path current) {
        try (var files = Files.list(directory)) {
            files.filter(Files::isRegularFile)
                    .filter(path -> !path.equals(current))
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return name.startsWith(FILE_PREFIX) && name.endsWith(FILE_SUFFIX);
                    })
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException ignored) {
        }
    }

    private static void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}

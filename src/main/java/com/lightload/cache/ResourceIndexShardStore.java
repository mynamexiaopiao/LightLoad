package com.lightload.cache;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Compact per-mod persistence for the only demonstrated hot path: resource existence. */
public final class ResourceIndexShardStore {
    private final Path directory;
    private final String minecraftVersion;
    private final String forgeVersion;

    public ResourceIndexShardStore(Path cacheDirectory, String minecraftVersion, String forgeVersion) {
        this.directory = cacheDirectory.resolve("resource-cache-v1").resolve("hasResource");
        this.minecraftVersion = minecraftVersion;
        this.forgeVersion = forgeVersion;
    }

    public Optional<Map<String, Boolean>> loadExistences(String id, PackFingerprint fingerprint) {
        Path path = directory.resolve(id + ".bin");
        if (!Files.isRegularFile(path)) return Optional.empty();
        try {
            return ResourceExistenceStore.read(path, minecraftVersion, forgeVersion, fingerprint);
        } catch (IOException | RuntimeException ignored) {
            return Optional.empty();
        }
    }

    public void writeExistences(String id, PackFingerprint fingerprint, Map<String, Boolean> existences)
            throws IOException {
        ResourceExistenceStore.write(directory.resolve(id + ".bin"), minecraftVersion, forgeVersion,
                fingerprint, existences);
    }

    public Optional<Map<String, java.util.List<String>>> loadPaths(String id, PackFingerprint fingerprint) {
        Path path = directory.resolveSibling("paths").resolve(id + ".bin");
        if (!Files.isRegularFile(path)) return Optional.empty();
        try {
            return ResourcePathStore.read(path, minecraftVersion, forgeVersion, fingerprint);
        } catch (IOException | RuntimeException ignored) {
            return Optional.empty();
        }
    }

    public Map<String, ResourcePathStore.PersistedPaths> loadAllPaths() {
        Path pathsDirectory = directory.resolveSibling("paths");
        Map<String, ResourcePathStore.PersistedPaths> result = new ConcurrentHashMap<>();
        if (!Files.isDirectory(pathsDirectory)) return result;
        try (var files = Files.list(pathsDirectory)) {
            files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".bin"))
                    .forEach(path -> {
                        String fileName = path.getFileName().toString();
                        String id = fileName.substring(0, fileName.length() - 4);
                        try {
                            ResourcePathStore.read(path, minecraftVersion, forgeVersion)
                                    .ifPresent(persisted -> result.put(id, persisted));
                        } catch (IOException | RuntimeException ignored) {
                        }
                    });
        } catch (IOException ignored) {
        }
        return result;
    }

    public void writePaths(String id, PackFingerprint fingerprint,
                           Map<String, java.util.List<String>> paths) throws IOException {
        ResourcePathStore.write(directory.resolveSibling("paths").resolve(id + ".bin"),
                minecraftVersion, forgeVersion, fingerprint, paths);
    }

    public long diskBytes() {
        return directoryBytes(directory) + directoryBytes(directory.resolveSibling("paths"));
    }

    private static long directoryBytes(Path directory) {
        if (!Files.isDirectory(directory)) return 0;
        try (var files = Files.list(directory)) {
            return files.filter(Files::isRegularFile).mapToLong(ResourceIndexShardStore::fileSize).sum();
        } catch (IOException ignored) {
            return 0;
        }
    }

    private static long fileSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException ignored) {
            return 0;
        }
    }
}

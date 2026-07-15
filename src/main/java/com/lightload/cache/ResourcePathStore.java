package com.lightload.cache;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** Fingerprinted per-pack namespace and relative-path index. */
final class ResourcePathStore {
    private static final int MAGIC = 0x4C4C5031;
    private static final int SCHEMA = 1;
    private static final int MAX_GROUPS = 100_000;
    private static final int MAX_PATHS = 10_000_000;

    private ResourcePathStore() {
    }

    static Optional<Map<String, List<String>>> read(Path path, String minecraftVersion, String forgeVersion,
                                                    PackFingerprint expected) throws IOException {
        return read(path, minecraftVersion, forgeVersion)
                .filter(persisted -> persisted.fingerprint().equals(expected))
                .map(PersistedPaths::groups);
    }

    static Optional<PersistedPaths> read(Path path, String minecraftVersion, String forgeVersion)
            throws IOException {
        if (!Files.isRegularFile(path)) return Optional.empty();
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            if (input.readInt() != MAGIC || input.readInt() != SCHEMA) throw new IOException("Unsupported path cache");
            if (!minecraftVersion.equals(input.readUTF()) || !forgeVersion.equals(input.readUTF())) return Optional.empty();
            PackFingerprint fingerprint = new PackFingerprint(
                    input.readUTF(), input.readUTF(), input.readLong(), input.readLong());
            int groups = count(input.readInt(), MAX_GROUPS);
            int totalPaths = 0;
            Map<String, List<String>> result = new HashMap<>(groups);
            for (int group = 0; group < groups; group++) {
                String key = input.readUTF();
                int size = count(input.readInt(), MAX_PATHS - totalPaths);
                totalPaths += size;
                List<String> paths = new ArrayList<>(size);
                for (int index = 0; index < size; index++) paths.add(input.readUTF());
                result.put(key, List.copyOf(paths));
            }
            if (input.read() != -1) throw new IOException("Trailing path cache data");
            return Optional.of(new PersistedPaths(fingerprint, Map.copyOf(result)));
        } catch (EOFException exception) {
            throw new IOException("Truncated path cache", exception);
        }
    }

    static void write(Path path, String minecraftVersion, String forgeVersion,
                      PackFingerprint fingerprint, Map<String, List<String>> groups) throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temporary)))) {
            output.writeInt(MAGIC);
            output.writeInt(SCHEMA);
            output.writeUTF(minecraftVersion);
            output.writeUTF(forgeVersion);
            output.writeUTF(fingerprint.packId());
            output.writeUTF(fingerprint.source());
            output.writeLong(fingerprint.size());
            output.writeLong(fingerprint.modifiedMillis());
            TreeMap<String, List<String>> sorted = new TreeMap<>(groups);
            output.writeInt(sorted.size());
            for (Map.Entry<String, List<String>> entry : sorted.entrySet()) {
                output.writeUTF(entry.getKey());
                output.writeInt(entry.getValue().size());
                for (String value : entry.getValue()) output.writeUTF(value);
            }
        }
        try {
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static int count(int value, int maximum) throws IOException {
        if (value < 0 || value > maximum) throw new IOException("Invalid path cache size: " + value);
        return value;
    }

    record PersistedPaths(PackFingerprint fingerprint, Map<String, List<String>> groups) {
    }
}

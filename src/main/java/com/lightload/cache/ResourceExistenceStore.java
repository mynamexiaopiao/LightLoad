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
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

final class ResourceExistenceStore {
    private static final int MAGIC = 0x4C4C4531;
    private static final int SCHEMA = 1;
    private static final int LEGACY_MAGIC = 0x4C4C4931;
    private static final int LEGACY_SCHEMA = 4;
    private static final int MAX_ENTRIES = 10_000_000;

    private ResourceExistenceStore() {
    }

    static Optional<Map<String, Boolean>> read(Path path, String minecraftVersion, String forgeVersion,
                                               PackFingerprint expected) throws IOException {
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            int magic = input.readInt();
            int schema = input.readInt();
            if (magic == MAGIC && schema == SCHEMA) {
                verifyVersions(input, minecraftVersion, forgeVersion);
                PackFingerprint fingerprint = readFingerprint(input);
                Map<String, Boolean> existences = readExistences(input);
                return fingerprint.equals(expected) ? Optional.of(Map.copyOf(existences)) : Optional.empty();
            }
            if (magic == LEGACY_MAGIC && schema == LEGACY_SCHEMA) {
                return readLegacy(input, minecraftVersion, forgeVersion, expected);
            }
            throw new IOException("Unsupported existence cache format");
        } catch (EOFException exception) {
            throw new IOException("Truncated existence cache", exception);
        }
    }

    static void write(Path path, String minecraftVersion, String forgeVersion,
                      PackFingerprint fingerprint, Map<String, Boolean> existences) throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temporary)))) {
            output.writeInt(MAGIC);
            output.writeInt(SCHEMA);
            output.writeUTF(minecraftVersion);
            output.writeUTF(forgeVersion);
            writeFingerprint(output, fingerprint);
            output.writeInt(existences.size());
            for (Map.Entry<String, Boolean> entry : new java.util.TreeMap<>(existences).entrySet()) {
                output.writeUTF(entry.getKey());
                output.writeBoolean(entry.getValue());
            }
        }
        try {
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Optional<Map<String, Boolean>> readLegacy(DataInputStream input, String minecraftVersion,
                                                              String forgeVersion, PackFingerprint expected)
            throws IOException {
        verifyVersions(input, minecraftVersion, forgeVersion);
        int packCount = count(input.readInt());
        for (int i = 0; i < packCount; i++) {
            PackFingerprint fingerprint = readFingerprint(input);
            int namespaceGroups = count(input.readInt());
            for (int group = 0; group < namespaceGroups; group++) {
                input.readUTF();
                int namespaces = count(input.readInt());
                for (int j = 0; j < namespaces; j++) input.readUTF();
            }
            int manifests = count(input.readInt());
            for (int manifest = 0; manifest < manifests; manifest++) {
                input.readUTF();
                input.readUTF();
                int resources = count(input.readInt());
                for (int j = 0; j < resources; j++) input.readUTF();
            }
            Map<String, Boolean> existences = readExistences(input);
            if (fingerprint.equals(expected)) return Optional.of(Map.copyOf(existences));
        }
        return Optional.empty();
    }

    private static void verifyVersions(DataInputStream input, String minecraftVersion, String forgeVersion)
            throws IOException {
        if (!minecraftVersion.equals(input.readUTF()) || !forgeVersion.equals(input.readUTF())) {
            throw new IOException("Cache belongs to a different game or Forge version");
        }
    }

    private static PackFingerprint readFingerprint(DataInputStream input) throws IOException {
        return new PackFingerprint(input.readUTF(), input.readUTF(), input.readLong(), input.readLong());
    }

    private static void writeFingerprint(DataOutputStream output, PackFingerprint fingerprint) throws IOException {
        output.writeUTF(fingerprint.packId());
        output.writeUTF(fingerprint.source());
        output.writeLong(fingerprint.size());
        output.writeLong(fingerprint.modifiedMillis());
    }

    private static Map<String, Boolean> readExistences(DataInputStream input) throws IOException {
        int size = count(input.readInt());
        Map<String, Boolean> result = new HashMap<>(size);
        for (int i = 0; i < size; i++) result.put(input.readUTF(), input.readBoolean());
        return result;
    }

    private static int count(int value) throws IOException {
        if (value < 0 || value > MAX_ENTRIES) throw new IOException("Invalid collection size: " + value);
        return value;
    }
}

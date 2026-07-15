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
import java.util.TreeMap;
import java.util.zip.CRC32;

/** One sequential blob for small immutable resources, grouped by mod fingerprint. */
public final class ResourceContentStore {
    private static final int MAGIC = 0x4C4C4333;
    private static final int SCHEMA = 1;
    private static final int MAX_PACKS = 100_000;
    private static final int MAX_ENTRIES = 10_000_000;
    private static final int MAX_ENTRY_BYTES = 16 * 1024 * 1024;
    private static final long MAX_TOTAL_BYTES = 1024L * 1024 * 1024;

    private ResourceContentStore() {
    }

    public static Map<String, PackContent> read(Path path, String minecraftVersion, String forgeVersion)
            throws IOException {
        if (!Files.isRegularFile(path)) return Map.of();
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(path), 1024 * 1024))) {
            if (input.readInt() != MAGIC || input.readInt() != SCHEMA) {
                throw new IOException("Unsupported content cache format");
            }
            if (!minecraftVersion.equals(input.readUTF()) || !forgeVersion.equals(input.readUTF())) {
                throw new IOException("Content cache belongs to a different game or Forge version");
            }
            int packCount = checked(input.readInt(), MAX_PACKS);
            long totalBytes = 0;
            Map<String, PackContent> packs = new HashMap<>(packCount);
            for (int packIndex = 0; packIndex < packCount; packIndex++) {
                PackFingerprint fingerprint = readFingerprint(input);
                int entryCount = checked(input.readInt(), MAX_ENTRIES);
                Map<String, byte[]> entries = new HashMap<>(entryCount);
                for (int entryIndex = 0; entryIndex < entryCount; entryIndex++) {
                    String key = input.readUTF();
                    int length = checked(input.readInt(), MAX_ENTRY_BYTES);
                    int expectedCrc = input.readInt();
                    totalBytes += length;
                    if (totalBytes > MAX_TOTAL_BYTES) throw new IOException("Content cache is too large");
                    byte[] bytes = input.readNBytes(length);
                    if (bytes.length != length || crc(bytes) != expectedCrc) {
                        throw new IOException("Corrupt content cache entry: " + key);
                    }
                    entries.put(key, bytes);
                }
                packs.put(fingerprint.key(), new PackContent(fingerprint, Map.copyOf(entries)));
            }
            if (input.read() != -1) throw new IOException("Trailing data in content cache");
            return Map.copyOf(packs);
        } catch (EOFException exception) {
            throw new IOException("Truncated content cache", exception);
        }
    }

    public static void write(Path path, String minecraftVersion, String forgeVersion,
                             Map<String, PackContent> packs) throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(
                Files.newOutputStream(temporary), 1024 * 1024))) {
            output.writeInt(MAGIC);
            output.writeInt(SCHEMA);
            output.writeUTF(minecraftVersion);
            output.writeUTF(forgeVersion);
            TreeMap<String, PackContent> sortedPacks = new TreeMap<>(packs);
            output.writeInt(sortedPacks.size());
            for (PackContent pack : sortedPacks.values()) {
                writeFingerprint(output, pack.fingerprint());
                TreeMap<String, byte[]> entries = new TreeMap<>(pack.entries());
                output.writeInt(entries.size());
                for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                    output.writeUTF(entry.getKey());
                    output.writeInt(entry.getValue().length);
                    output.writeInt(crc(entry.getValue()));
                    output.write(entry.getValue());
                }
            }
        }
        try {
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
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

    private static int checked(int value, int maximum) throws IOException {
        if (value < 0 || value > maximum) throw new IOException("Invalid collection size: " + value);
        return value;
    }

    private static int crc(byte[] bytes) {
        CRC32 crc = new CRC32();
        crc.update(bytes);
        return (int) crc.getValue();
    }

    public record PackContent(PackFingerprint fingerprint, Map<String, byte[]> entries) {
    }
}

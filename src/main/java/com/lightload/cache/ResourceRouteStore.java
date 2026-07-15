package com.lightload.cache;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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

/** CRC-protected persistent winner table for Forge's delegated mod resources. */
public final class ResourceRouteStore {
    private static final int MAGIC = 0x4C4C5231;
    private static final int SCHEMA = 1;
    private static final int MAX_LAYOUTS = 100_000;
    private static final int MAX_ENTRIES = 5_000_000;
    private static final int MAX_WINNER = 100_000;
    private static final int MAX_PAYLOAD_BYTES = 256 * 1024 * 1024;

    private ResourceRouteStore() {
    }

    public static Map<String, Map<String, Integer>> read(Path path, String minecraftVersion,
                                                          String forgeVersion) throws IOException {
        if (!Files.isRegularFile(path)) return Map.of();
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            if (input.readInt() != MAGIC || input.readInt() != SCHEMA) {
                throw new IOException("Unsupported resource route cache format");
            }
            int length = checked(input.readInt(), MAX_PAYLOAD_BYTES);
            int expectedCrc = input.readInt();
            byte[] payload = input.readNBytes(length);
            if (payload.length != length || crc(payload) != expectedCrc || input.read() != -1) {
                throw new IOException("Corrupt resource route cache");
            }
            return readPayload(payload, minecraftVersion, forgeVersion);
        } catch (EOFException exception) {
            throw new IOException("Truncated resource route cache", exception);
        }
    }

    public static void write(Path path, String minecraftVersion, String forgeVersion,
                             Map<String, Map<String, Integer>> layouts) throws IOException {
        byte[] payload = writePayload(minecraftVersion, forgeVersion, layouts);
        Files.createDirectories(path.getParent());
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(
                Files.newOutputStream(temporary)))) {
            output.writeInt(MAGIC);
            output.writeInt(SCHEMA);
            output.writeInt(payload.length);
            output.writeInt(crc(payload));
            output.write(payload);
        }
        try {
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException ignored) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Map<String, Map<String, Integer>> readPayload(byte[] payload, String minecraftVersion,
                                                                  String forgeVersion) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            if (!minecraftVersion.equals(input.readUTF()) || !forgeVersion.equals(input.readUTF())) {
                throw new IOException("Resource routes belong to a different game or Forge version");
            }
            int layoutCount = checked(input.readInt(), MAX_LAYOUTS);
            int totalEntries = 0;
            Map<String, Map<String, Integer>> layouts = new HashMap<>(layoutCount);
            for (int layoutIndex = 0; layoutIndex < layoutCount; layoutIndex++) {
                String layout = input.readUTF();
                int entryCount = checked(input.readInt(), MAX_ENTRIES - totalEntries);
                totalEntries += entryCount;
                Map<String, Integer> routes = new HashMap<>(entryCount);
                for (int entryIndex = 0; entryIndex < entryCount; entryIndex++) {
                    String resource = input.readUTF();
                    int winner = input.readInt();
                    if (winner < -1 || winner > MAX_WINNER) {
                        throw new IOException("Invalid resource route winner: " + winner);
                    }
                    routes.put(resource, winner);
                }
                layouts.put(layout, Map.copyOf(routes));
            }
            if (input.read() != -1) throw new IOException("Trailing resource route data");
            return Map.copyOf(layouts);
        }
    }

    private static byte[] writePayload(String minecraftVersion, String forgeVersion,
                                       Map<String, Map<String, Integer>> layouts) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeUTF(minecraftVersion);
            output.writeUTF(forgeVersion);
            TreeMap<String, Map<String, Integer>> sortedLayouts = new TreeMap<>(layouts);
            output.writeInt(sortedLayouts.size());
            for (Map.Entry<String, Map<String, Integer>> layout : sortedLayouts.entrySet()) {
                output.writeUTF(layout.getKey());
                TreeMap<String, Integer> sortedRoutes = new TreeMap<>(layout.getValue());
                output.writeInt(sortedRoutes.size());
                for (Map.Entry<String, Integer> route : sortedRoutes.entrySet()) {
                    output.writeUTF(route.getKey());
                    output.writeInt(route.getValue());
                }
            }
        }
        if (bytes.size() > MAX_PAYLOAD_BYTES) throw new IOException("Resource route cache is too large");
        return bytes.toByteArray();
    }

    private static int checked(int value, int maximum) throws IOException {
        if (value < 0 || value > maximum) throw new IOException("Invalid resource route size: " + value);
        return value;
    }

    private static int crc(byte[] bytes) {
        CRC32 crc = new CRC32();
        crc.update(bytes);
        return (int) crc.getValue();
    }
}

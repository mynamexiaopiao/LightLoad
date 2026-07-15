package com.lightload.agent;

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
import java.util.Set;

final class ModulePackageIndexStore {
    private static final int MAGIC = 0x4C4C4D50;
    private static final int SCHEMA = 1;
    private static final int MAX_ENTRIES = 16_384;
    private static final int MAX_PACKAGES = 2_000_000;

    private ModulePackageIndexStore() {
    }

    static Map<String, Set<String>> read(Path path) throws IOException {
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            if (input.readInt() != MAGIC || input.readInt() != SCHEMA) {
                throw new IOException("Unsupported module package index");
            }
            int entries = checked(input.readInt(), MAX_ENTRIES, "entry");
            Map<String, Set<String>> result = new HashMap<>(entries);
            int totalPackages = 0;
            for (int entry = 0; entry < entries; entry++) {
                String key = input.readUTF();
                int count = checked(input.readInt(), MAX_PACKAGES - totalPackages, "package");
                totalPackages += count;
                List<String> packages = new ArrayList<>(count);
                for (int index = 0; index < count; index++) packages.add(input.readUTF());
                result.put(key, Set.copyOf(packages));
            }
            if (input.read() != -1) throw new IOException("Trailing module package index data");
            return Map.copyOf(result);
        } catch (EOFException exception) {
            throw new IOException("Truncated module package index", exception);
        }
    }

    static void write(Path path, Map<String, Set<String>> entries) throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            List<String> keys = new ArrayList<>(entries.keySet());
            keys.sort(String::compareTo);
            try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temporary)))) {
                output.writeInt(MAGIC);
                output.writeInt(SCHEMA);
                output.writeInt(keys.size());
                for (String key : keys) {
                    output.writeUTF(key);
                    List<String> packages = new ArrayList<>(entries.get(key));
                    packages.sort(String::compareTo);
                    output.writeInt(packages.size());
                    for (String packageName : packages) output.writeUTF(packageName);
                }
            }
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException unsupportedAtomicMove) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static int checked(int count, int maximum, String kind) throws IOException {
        if (count < 0 || count > maximum) throw new IOException("Invalid module package " + kind + " count");
        return count;
    }
}

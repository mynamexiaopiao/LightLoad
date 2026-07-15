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
import java.util.List;

public final class JarJarIndexStore {
    private static final int MAGIC = 0x4C4C4A49;
    private static final int SCHEMA = 3;
    private static final int MAX_ENTRIES = 16_384;

    private JarJarIndexStore() {
    }

    public static JarJarIndex read(Path path) throws IOException {
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            if (input.readInt() != MAGIC || input.readInt() != SCHEMA) throw new IOException("Unsupported Jar-in-Jar index");
            String targetFingerprint = input.readUTF();
            int parentCount = checkedCount(input.readInt());
            List<ParentFingerprint> parents = new ArrayList<>(parentCount);
            for (int index = 0; index < parentCount; index++) {
                parents.add(new ParentFingerprint(input.readUTF(), input.readLong(), input.readLong()));
            }
            int dependencyCount = checkedCount(input.readInt());
            List<JarJarIndex.Dependency> dependencies = new ArrayList<>(dependencyCount);
            for (int index = 0; index < dependencyCount; index++) {
                int parentIndex = input.readInt();
                int pathCount = checkedCount(input.readInt());
                List<String> nestedPaths = new ArrayList<>(pathCount);
                for (int pathIndex = 0; pathIndex < pathCount; pathIndex++) nestedPaths.add(input.readUTF());
                dependencies.add(new JarJarIndex.Dependency(parentIndex, nestedPaths, input.readUTF()));
            }
            if (input.read() != -1) throw new IOException("Trailing Jar-in-Jar index data");
            return new JarJarIndex(targetFingerprint, parents, dependencies);
        } catch (EOFException exception) {
            throw new IOException("Truncated Jar-in-Jar index", exception);
        }
    }

    public static void write(Path path, JarJarIndex index) throws IOException {
        Files.createDirectories(path.getParent());
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        try {
            try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(temporary)))) {
                output.writeInt(MAGIC);
                output.writeInt(SCHEMA);
                output.writeUTF(index.targetFingerprint());
                output.writeInt(index.parents().size());
                for (ParentFingerprint parent : index.parents()) {
                    output.writeUTF(parent.path());
                    output.writeLong(parent.size());
                    output.writeLong(parent.modifiedMillis());
                }
                output.writeInt(index.dependencies().size());
                for (JarJarIndex.Dependency dependency : index.dependencies()) {
                    output.writeInt(dependency.parentIndex());
                    output.writeInt(dependency.nestedPaths().size());
                    for (String nestedPath : dependency.nestedPaths()) output.writeUTF(nestedPath);
                    output.writeUTF(dependency.identity());
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

    private static int checkedCount(int count) throws IOException {
        if (count < 0 || count > MAX_ENTRIES) throw new IOException("Invalid Jar-in-Jar index entry count");
        return count;
    }
}

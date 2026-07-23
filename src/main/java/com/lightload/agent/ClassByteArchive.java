package com.lightload.agent;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.module.ModuleReference;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.Inflater;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/** Persistent, fingerprint-scoped archive of pre-transform module class bytes. */
final class ClassByteArchive {
    private static final int MAGIC = 0x4c4c4342;
    private static final int SCHEMA = 3;
    private static final int MAX_FILE_BYTES = 1_500_000_000;
    private static final int MAX_MODULES = 16_384;
    private static final int MAX_ENTRIES = 500_000;
    private static final int MAX_STRING_BYTES = 1_048_576;
    private static final int MAX_CLASS_BYTES = 64 * 1024 * 1024;
    private static final byte[] EMPTY_BYTES = new byte[0];

    private final Path basePath;
    private final String fingerprint;
    private final Map<String, Map<String, Segment>> cached;
    private final byte[] expanded;
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, byte[]>> recorded =
            new ConcurrentHashMap<>();
    private final Map<ModuleReference, String> moduleKeys = Collections.synchronizedMap(new IdentityHashMap<>());
    private final LongAdder hits = new LongAdder();
    private final LongAdder misses = new LongAdder();
    private final LongAdder recordedBytes = new LongAdder();
    private volatile boolean recordingEnabled;

    private ClassByteArchive(Path basePath, String fingerprint, Map<String, Map<String, Segment>> cached,
                             byte[] expanded, boolean recordingEnabled) {
        this.basePath = basePath;
        this.fingerprint = fingerprint;
        this.cached = cached;
        this.expanded = expanded;
        this.recordingEnabled = recordingEnabled;
    }

    static ClassByteArchive open(Path basePath, String fingerprint) {
        Path selected = selectedArchive(basePath);
        if (selected != null && Files.isRegularFile(selected)) {
            try {
                ClassByteArchive archive = read(basePath, selected, fingerprint);
                System.setProperty("lightload.runtimePatch.classArchiveBytes", Long.toString(Files.size(selected)));
                archive.publish("loaded");
                deleteStaleGeneration(basePath, selected);
                return archive;
            } catch (FingerprintMismatch ignored) {
                System.setProperty("lightload.runtimePatch.classArchive", "stale");
            } catch (Throwable throwable) {
                System.setProperty("lightload.runtimePatch.classArchive", "invalid");
                System.setProperty("lightload.runtimePatch.classArchiveDetail", throwable.toString());
            }
        }
        ClassByteArchive archive = new ClassByteArchive(basePath, fingerprint, Map.of(), null, true);
        archive.publish("recording");
        return archive;
    }

    byte[] cached(ModuleReference reference, String className) {
        Map<String, Segment> classes = cached.get(moduleKey(reference));
        Segment segment = classes == null ? null : classes.get(className);
        if (segment == null) {
            misses.increment();
            publishCounters();
            return null;
        }
        hits.increment();
        publishCounters();
        if (segment.length == 0) return EMPTY_BYTES;
        byte[] result = new byte[segment.length];
        System.arraycopy(expanded, segment.offset, result, 0, result.length);
        return result;
    }

    void record(ModuleReference reference, String className, byte[] bytes) {
        if (!recordingEnabled || bytes.length > MAX_CLASS_BYTES) return;
        long current = recordedBytes.sum();
        if (current + bytes.length > MAX_FILE_BYTES) {
            recordingEnabled = false;
            System.setProperty("lightload.runtimePatch.classArchive", "record-limit");
            return;
        }
        byte[] stable = bytes.length == 0 ? EMPTY_BYTES : bytes.clone();
        byte[] previous = recorded.computeIfAbsent(moduleKey(reference), ignored -> new ConcurrentHashMap<>())
                .putIfAbsent(className, stable);
        if (previous == null) recordedBytes.add(stable.length);
    }

    synchronized void finish() {
        if (!recordingEnabled || recorded.isEmpty()) return;
        recordingEnabled = false;
        try {
            writeAlternate();
            publish("persisted");
            recorded.clear();
        } catch (Throwable throwable) {
            publish("persist-failed");
            System.setProperty("lightload.runtimePatch.classArchiveDetail", throwable.toString());
        }
    }

    long hitCount() {
        return hits.sum();
    }

    long missCount() {
        return misses.sum();
    }

    int cachedEntryCount() {
        return cached.values().stream().mapToInt(Map::size).sum();
    }

    private int recordedEntryCount() {
        return recorded.values().stream().mapToInt(Map::size).sum();
    }

    private static ClassByteArchive read(Path basePath, Path path, String expectedFingerprint) throws IOException {
        long size = Files.size(path);
        if (size < 16 || size > MAX_FILE_BYTES) throw new IOException("Invalid class archive size: " + size);
        byte[] archive = Files.readAllBytes(path);
        ByteBuffer input = ByteBuffer.wrap(archive);
        if (input.getInt() != MAGIC || input.getInt() != SCHEMA) {
            throw new IOException("Unsupported class byte archive");
        }
        String storedFingerprint = readString(input);
        if (!storedFingerprint.equals(expectedFingerprint)) throw new FingerprintMismatch();
        int moduleCount = checked(input.getInt(), MAX_MODULES, "module");
        List<String> modules = new ArrayList<>(moduleCount);
        for (int index = 0; index < moduleCount; index++) modules.add(readString(input));
        int entryCount = checked(input.getInt(), MAX_ENTRIES, "entry");
        List<PendingSegment> pending = new ArrayList<>(entryCount);
        long dataBytes = 0;
        for (int index = 0; index < entryCount; index++) {
            int module = checkedIndex(input.getInt(), moduleCount, "module");
            String className = readString(input);
            int length = checked(input.getInt(), MAX_CLASS_BYTES, "class byte");
            dataBytes += length;
            if (dataBytes > MAX_FILE_BYTES) throw new IOException("Class byte archive is too large");
            pending.add(new PendingSegment(module, className, length));
        }
        int dataStart = input.position();
        if (dataStart >= archive.length && dataBytes != 0) throw new IOException("Missing class byte data");
        byte[] expanded = inflate(archive, dataStart, archive.length - dataStart, (int) dataBytes);
        Map<String, Map<String, Segment>> modulesByKey = new HashMap<>(moduleCount);
        int offset = dataStart;
        for (PendingSegment entry : pending) {
            modulesByKey.computeIfAbsent(normalizeStoredModuleKey(modules.get(entry.module)), ignored -> new HashMap<>())
                    .put(entry.className, new Segment(offset - dataStart, entry.length));
            offset += entry.length;
        }
        Map<String, Map<String, Segment>> immutable = new HashMap<>(modulesByKey.size());
        modulesByKey.forEach((module, classes) -> immutable.put(module, Map.copyOf(classes)));
        return new ClassByteArchive(basePath, expectedFingerprint, Map.copyOf(immutable), expanded, false);
    }

    private void writeAlternate() throws IOException {
        Files.createDirectories(basePath.getParent());
        String current = readPointer(basePath);
        String next = "a".equals(current) ? "b" : "a";
        Path target = archivePath(basePath, next);
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        List<ModuleRecord> modules = new ArrayList<>(recorded.size());
        recorded.forEach((module, classes) -> {
            List<ClassRecord> records = new ArrayList<>(classes.size());
            classes.forEach((className, bytes) -> records.add(new ClassRecord(className, bytes)));
            records.sort(Comparator.comparing(ClassRecord::className));
            modules.add(new ModuleRecord(module, records));
        });
        modules.sort(Comparator.comparing(ModuleRecord::moduleKey));
        int entries = modules.stream().mapToInt(module -> module.classes.size()).sum();
        if (entries > MAX_ENTRIES) throw new IOException("Too many class archive entries: " + entries);
        try {
            try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(
                    Files.newOutputStream(temporary)))) {
                output.writeInt(MAGIC);
                output.writeInt(SCHEMA);
                writeString(output, fingerprint);
                output.writeInt(modules.size());
                for (ModuleRecord module : modules) writeString(output, module.moduleKey);
                output.writeInt(entries);
                for (int moduleIndex = 0; moduleIndex < modules.size(); moduleIndex++) {
                    for (ClassRecord record : modules.get(moduleIndex).classes) {
                        output.writeInt(moduleIndex);
                        writeString(output, record.className);
                        output.writeInt(record.bytes.length);
                    }
                }
                writeCompressedData(output, modules);
            }
            moveReplacing(temporary, target);
            writePointer(basePath, next);
            System.setProperty("lightload.runtimePatch.classArchiveEntries", Integer.toString(entries));
            System.setProperty("lightload.runtimePatch.classArchiveBytes", Long.toString(Files.size(target)));
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void publish(String status) {
        System.setProperty("lightload.runtimePatch.classArchive", status);
        System.setProperty("lightload.runtimePatch.classArchiveEntries",
                Integer.toString(cachedEntryCount() + recordedEntryCount()));
        publishCounters();
    }

    private void publishCounters() {
        System.setProperty("lightload.runtimePatch.classArchiveHits", Long.toString(hits.sum()));
        System.setProperty("lightload.runtimePatch.classArchiveMisses", Long.toString(misses.sum()));
    }

    private static void writeCompressedData(DataOutputStream output, List<ModuleRecord> modules)
            throws IOException {
        try (DeflaterOutputStream compressed = new DeflaterOutputStream(output,
                new Deflater(Deflater.DEFAULT_COMPRESSION, true), 8192)) {
            for (ModuleRecord module : modules) {
                for (ClassRecord record : module.classes) {
                    compressed.write(record.bytes);
                }
            }
        }
    }

    private static byte[] inflate(byte[] archive, int offset, int length, int rawLength) throws IOException {
        if (rawLength == 0) return new byte[0];
        Inflater inflater = new Inflater(true);
        try {
            inflater.setInput(archive, offset, length);
            byte[] result = new byte[rawLength];
            int cursor = 0;
            while (!inflater.finished()) {
                if (cursor == result.length) throw new IOException("Class archive expands beyond metadata");
                int count;
                try {
                    count = inflater.inflate(result, cursor, result.length - cursor);
                } catch (DataFormatException exception) {
                    throw new IOException("Invalid compressed class archive", exception);
                }
                if (count == 0 && (inflater.needsDictionary() || inflater.needsInput())) {
                    throw new IOException("Truncated compressed class archive");
                }
                cursor += count;
            }
            if (cursor != rawLength || inflater.getRemaining() != 0) {
                throw new IOException("Compressed class archive length mismatch");
            }
            return result;
        } finally {
            inflater.end();
        }
    }

    private String moduleKey(ModuleReference reference) {
        String existing = moduleKeys.get(reference);
        if (existing != null) return existing;
        String created = reference.descriptor().name() + '\n'
                + normalizeLocation(reference.location().map(Object::toString).orElse(""));
        moduleKeys.put(reference, created);
        return created;
    }

    private static String normalizeStoredModuleKey(String key) {
        int separator = key.indexOf('\n');
        if (separator < 0) return key;
        return key.substring(0, separator + 1) + normalizeLocation(key.substring(separator + 1));
    }

    private static String normalizeLocation(String location) {
        StringBuilder normalized = null;
        int copied = 0;
        for (int index = 0; index < location.length(); index++) {
            int digits;
            if (location.charAt(index) == '#') digits = index + 1;
            else if (index + 2 < location.length() && location.charAt(index) == '%'
                    && location.charAt(index + 1) == '2' && location.charAt(index + 2) == '3') digits = index + 3;
            else continue;
            int end = digits;
            while (end < location.length()) {
                char value = location.charAt(end);
                if (value < '0' || value > '9') break;
                end++;
            }
            if (end == digits) continue;
            if (end < location.length() && location.charAt(end) == '_') end++;
            if (normalized == null) normalized = new StringBuilder(location.length());
            normalized.append(location, copied, index);
            copied = end;
            index = end - 1;
        }
        if (normalized == null) return location;
        return normalized.append(location, copied, location.length()).toString();
    }

    private static Path selectedArchive(Path basePath) {
        String selected = readPointer(basePath);
        return selected == null ? null : archivePath(basePath, selected);
    }

    private static String readPointer(Path basePath) {
        try {
            Path pointer = pointerPath(basePath);
            if (!Files.isRegularFile(pointer)) return null;
            String value = Files.readString(pointer, StandardCharsets.US_ASCII).trim();
            return "a".equals(value) || "b".equals(value) ? value : null;
        } catch (IOException ignored) {
            return null;
        }
    }

    private static void writePointer(Path basePath, String value) throws IOException {
        Path pointer = pointerPath(basePath);
        Path temporary = pointer.resolveSibling(pointer.getFileName() + ".tmp");
        try {
            Files.writeString(temporary, value, StandardCharsets.US_ASCII);
            moveReplacing(temporary, pointer);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void moveReplacing(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException unsupportedAtomicMove) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Path pointerPath(Path basePath) {
        return basePath.resolveSibling(basePath.getFileName() + ".current");
    }

    private static Path archivePath(Path basePath, String generation) {
        return basePath.resolveSibling(basePath.getFileName().toString() + '-' + generation + ".bin");
    }

    private static void deleteStaleGeneration(Path basePath, Path selected) {
        String name = selected.getFileName().toString();
        String stale = name.endsWith("-a.bin") ? "b" : name.endsWith("-b.bin") ? "a" : null;
        if (stale == null) return;
        try {
            Files.deleteIfExists(archivePath(basePath, stale));
        } catch (IOException ignored) {
        }
    }

    private static String readString(ByteBuffer input) throws IOException {
        int length = checked(input.getInt(), MAX_STRING_BYTES, "string byte");
        if (length > input.remaining()) throw new IOException("Truncated class archive string");
        byte[] bytes = new byte[length];
        input.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void writeString(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_STRING_BYTES) throw new IOException("Class archive string is too long");
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static int checked(int value, int maximum, String kind) throws IOException {
        if (value < 0 || value > maximum) throw new IOException("Invalid " + kind + " count: " + value);
        return value;
    }

    private static int checkedIndex(int value, int maximum, String kind) throws IOException {
        if (value < 0 || value >= maximum) throw new IOException("Invalid " + kind + " index: " + value);
        return value;
    }

    private record Segment(int offset, int length) {
    }

    private record PendingSegment(int module, String className, int length) {
    }

    private record ClassRecord(String className, byte[] bytes) {
    }

    private record ModuleRecord(String moduleKey, List<ClassRecord> classes) {
    }

    private static final class FingerprintMismatch extends IOException {
    }
}



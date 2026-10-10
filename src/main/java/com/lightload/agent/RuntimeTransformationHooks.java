package com.lightload.agent;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Field;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.module.ModuleReader;
import java.lang.module.ModuleReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Bootstrap-visible exact index for Forge access-transformer targets. */
public final class RuntimeTransformationHooks {
    private static final boolean[] OCULUS_PATH_CHARS = createOculusPathChars();
    private static final Map<Map<?, ?>, Index> INDEXES = Collections.synchronizedMap(new IdentityHashMap<>());
    private static final Map<List<?>, MixinConfigIndex> MIXIN_CONFIG_INDEXES =
            Collections.synchronizedMap(new IdentityHashMap<>());
    private static final Map<Set<?>, ObjectHolderIndex> OBJECT_HOLDER_INDEXES =
            Collections.synchronizedMap(new IdentityHashMap<>());
    private static final AtomicBoolean MIXIN_INDEX_FAILURE_REPORTED = new AtomicBoolean();
    private static final Map<String, Set<String>> MODULE_PACKAGES = new ConcurrentHashMap<>();
    private static final LongAdder MODULE_PACKAGE_HITS = new LongAdder();
    private static final LongAdder MODULE_PACKAGE_MISSES = new LongAdder();
    private static final LongAdder MODULE_CLOSURE_SKIPS = new LongAdder();
    private static final LongAdder MODULE_CLOSURE_FALLBACKS = new LongAdder();
    private static final ScheduledExecutorService MODULE_CACHE_WRITER = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Lightload-Module-Cache");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });
    private static volatile Path modulePackageIndexPath;
    private static volatile ScheduledFuture<?> modulePackageWrite;
    private static volatile Path classByteArchivePath;
    private static volatile ClassByteArchive classByteArchive;
    private static volatile boolean invoked;
    private static volatile boolean fallbackReported;
    private static final ClassValue<Map<String, String[]>> OPCODE_NAMES = new ClassValue<>() {
        @Override
        protected Map<String, String[]> computeValue(Class<?> type) {
            return new ConcurrentHashMap<>();
        }
    };

    private static final ClassValue<Method> GET_TARGET = new ClassValue<>() {
        @Override
        protected Method computeValue(Class<?> type) {
            return publicMethod(type, "getTarget");
        }
    };
    private static final ClassValue<TargetMethods> TARGET_METHODS = new ClassValue<>() {
        @Override
        protected TargetMethods computeValue(Class<?> type) {
            return new TargetMethods(publicMethod(type, "getASMType"), publicMethod(type, "getType"),
                    publicMethod(type, "targetName"));
        }
    };
    private static final ClassValue<MixinConfigMethods> MIXIN_CONFIG_METHODS = new ClassValue<>() {
        @Override
        protected MixinConfigMethods computeValue(Class<?> type) {
            return new MixinConfigMethods(accessibleMethod(type, "getMixinPackage"),
                    accessibleMethod(type, "getTargetsSet"));
        }
    };
    private static final ClassValue<SecureJarMethods> SECURE_JAR_METHODS = new ClassValue<>() {
        @Override
        protected SecureJarMethods computeValue(Class<?> type) {
            return new SecureJarMethods(inheritedPublicMethod(type, "getPrimaryPath", 0),
                    inheritedPublicMethod(type, "getPackages", 0));
        }
    };
    private static final ClassValue<ModuleMetadataMethods> MODULE_METADATA_METHODS = new ClassValue<>() {
        @Override
        protected ModuleMetadataMethods computeValue(Class<?> type) {
            return new ModuleMetadataMethods(inheritedPublicMethod(type, "name", 0),
                    inheritedPublicMethod(type, "version", 0));
        }
    };

    private RuntimeTransformationHooks() {
    }

    public static byte[] readClassBytes(ModuleReader reader, ModuleReference reference, String className) {
        ClassByteArchive archive = classByteArchive;
        if (archive != null) {
            byte[] cached = archive.cached(reference, className);
            if (cached != null) return cached;
        }
        String resource = className.replace('.', '/') + ".class";
        try {
            java.util.Optional<InputStream> opened = reader.open(resource);
            if (opened.isEmpty()) {
                byte[] missing = new byte[0];
                if (archive != null) archive.record(reference, className, missing);
                return missing;
            }
            try (InputStream input = opened.get()) {
                byte[] bytes = input.readAllBytes();
                if (archive != null) archive.record(reference, className, bytes);
                return bytes;
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    public static void configureClassByteCache(Path path) {
        classByteArchivePath = path;
        System.setProperty("lightload.runtimePatch.classArchive", "waiting-fingerprint");
    }

    public static void activateClassByteCache(String targetFingerprint, List<ParentFingerprint> parents) {
        Path path = classByteArchivePath;
        if (path == null || classByteArchive != null) return;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateDigest(digest, targetFingerprint);
            for (ParentFingerprint parent : parents) {
                updateDigest(digest, parent.path());
                updateDigest(digest, Long.toString(parent.size()));
                updateDigest(digest, Long.toString(parent.modifiedMillis()));
            }
            classByteArchive = ClassByteArchive.open(path, HexFormat.of().formatHex(digest.digest()));
        } catch (Throwable throwable) {
            System.setProperty("lightload.runtimePatch.classArchive", "activation-failed");
            System.setProperty("lightload.runtimePatch.classArchiveDetail", throwable.toString());
        }
    }

    /** Activates the same archive for dynamic attach without requiring a premain fingerprint hook. */
    public static void activateRuntimeClassByteCache(Path gameDirectory) {
        Path path = classByteArchivePath;
        if (path == null || classByteArchive != null) return;
        try {
            String fingerprint = runtimeClassByteFingerprint(
                    gameDirectory, System.getProperty("java.class.path", ""));
            classByteArchive = ClassByteArchive.open(path, fingerprint);
        } catch (Throwable throwable) {
            System.setProperty("lightload.runtimePatch.classArchive", "activation-failed");
            System.setProperty("lightload.runtimePatch.classArchiveDetail", throwable.toString());
        }
    }

    static String runtimeClassByteFingerprint(Path gameDirectory, String classPath) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        updateDigest(digest, "lightload-runtime-classpath-v2");
        updateDigest(digest, System.getProperty("java.runtime.version", "unknown"));

        List<Path> roots = new ArrayList<>();
        if (classPath != null && !classPath.isBlank()) {
            for (String entry : classPath.split(java.util.regex.Pattern.quote(
                    System.getProperty("path.separator")))) {
                if (!entry.isBlank()) roots.add(Path.of(entry));
            }
        }
        Path mods = gameDirectory == null ? null : gameDirectory.resolve("mods");
        if (mods != null && Files.isDirectory(mods)) {
            updateDigest(digest, "mods-directory");
            updateShallowDirectoryDigest(digest, mods);
        }
        roots.sort(Comparator.comparing(path -> path.toAbsolutePath().normalize().toString()));
        for (Path root : roots) updatePathDigest(digest, root);
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void updateShallowDirectoryDigest(MessageDigest digest, Path directory) throws IOException {
        List<Path> files;
        try (var stream = Files.list(directory)) {
            files = stream.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                    .toList();
        }
        updateDigest(digest, Integer.toString(files.size()));
        for (Path file : files) updateFileDigest(digest, file, file.getFileName().toString());
    }

    private static void updatePathDigest(MessageDigest digest, Path root) throws IOException {
        Path normalized = root.toAbsolutePath().normalize();
        updateDigest(digest, normalized.toString());
        if (!Files.exists(normalized)) {
            updateDigest(digest, "missing");
            return;
        }
        if (Files.isRegularFile(normalized)) {
            updateFileDigest(digest, normalized, normalized.getFileName().toString());
            return;
        }
        if (!Files.isDirectory(normalized)) {
            updateDigest(digest, "unsupported");
            return;
        }
        try (var files = Files.walk(normalized)) {
            List<Path> entries = files.filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(path -> normalized.relativize(path).toString()))
                    .toList();
            updateDigest(digest, Integer.toString(entries.size()));
            for (Path entry : entries) {
                updateFileDigest(digest, entry, normalized.relativize(entry).toString());
            }
        }
    }

    private static void updateFileDigest(MessageDigest digest, Path file, String identity) throws IOException {
        updateDigest(digest, identity);
        updateDigest(digest, Long.toString(Files.size(file)));
        updateDigest(digest, Long.toString(Files.getLastModifiedTime(file).toMillis()));
    }

    public static void finishClassByteCache() {
        ClassByteArchive archive = classByteArchive;
        if (archive != null) archive.finish();
    }

    private static void updateDigest(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }

    public static void configureModulePackageCache(Path path) {
        modulePackageIndexPath = path;
        if (!Files.isRegularFile(path)) {
            System.setProperty("lightload.runtimePatch.modulePackages", "empty");
            return;
        }
        try {
            MODULE_PACKAGES.putAll(ModulePackageIndexStore.read(path));
            System.setProperty("lightload.runtimePatch.modulePackages", "loaded");
            System.setProperty("lightload.runtimePatch.modulePackageEntries",
                    Integer.toString(MODULE_PACKAGES.size()));
        } catch (Throwable throwable) {
            System.setProperty("lightload.runtimePatch.modulePackages", "invalid");
            try {
                Files.deleteIfExists(path);
            } catch (Exception ignored) {
            }
        }
    }

    @SuppressWarnings("unchecked")
    public static Set<String> modulePackages(Object secureJar, Object metadata) {
        try {
            String key = modulePackageKey(secureJar, metadata);
            if (key != null) {
                Set<String> cached = MODULE_PACKAGES.get(key);
                if (cached != null) {
                    MODULE_PACKAGE_HITS.increment();
                    publishModulePackageStats();
                    return cached;
                }
            }

            Object value = SECURE_JAR_METHODS.get(secureJar.getClass()).packages().invoke(secureJar);
            if (!(value instanceof Set<?> packages)) {
                throw new IllegalStateException("SecureJar packages are not a Set");
            }
            if (key == null) return (Set<String>) packages;

            Set<String> captured = Set.copyOf((Set<String>) packages);
            Set<String> existing = MODULE_PACKAGES.putIfAbsent(key, captured);
            MODULE_PACKAGE_MISSES.increment();
            publishModulePackageStats();
            scheduleModulePackageWrite();
            return existing == null ? captured : existing;
        } catch (RuntimeException exception) {
            throw exception;
        } catch (Throwable throwable) {
            throw new IllegalStateException("Could not resolve SecureJar packages", throwable);
        }
    }

    @SuppressWarnings("unchecked")
    public static void completeTransitiveClosure(Map<?, ?> readsGraph, Map<?, ?> transitiveGraph) {
        Set<Object> required = new HashSet<>();
        for (Object value : transitiveGraph.values()) {
            if (value instanceof Set<?> set) required.addAll(set);
        }
        for (Map.Entry<?, ?> entry : readsGraph.entrySet()) {
            Object owner = entry.getKey();
            Set<Object> reads = (Set<Object>) entry.getValue();
            if (containsAllExceptOwner(reads, required, owner)) {
                if (!reads.contains(owner) && reachesOwner(reads, transitiveGraph, owner)) reads.add(owner);
                MODULE_CLOSURE_SKIPS.increment();
                continue;
            }
            MODULE_CLOSURE_FALLBACKS.increment();
            ArrayDeque<Object> pending = new ArrayDeque<>(reads);
            while (!pending.isEmpty()) {
                Object dependency = pending.removeFirst();
                Object value = transitiveGraph.get(dependency);
                if (!(value instanceof Set<?> transitive)) continue;
                for (Object candidate : transitive) {
                    if (reads.add(candidate)) pending.addLast(candidate);
                }
            }
        }
        System.setProperty("lightload.runtimePatch.moduleClosureSkips", Long.toString(MODULE_CLOSURE_SKIPS.sum()));
        System.setProperty("lightload.runtimePatch.moduleClosureFallbacks",
                Long.toString(MODULE_CLOSURE_FALLBACKS.sum()));
        System.setProperty("lightload.runtimePatch.moduleClosureGraphSize", Integer.toString(readsGraph.size()));
    }

    private static boolean containsAllExceptOwner(Set<?> reads, Set<?> required, Object owner) {
        for (Object dependency : required) {
            if (!dependency.equals(owner) && !reads.contains(dependency)) return false;
        }
        return true;
    }

    private static boolean reachesOwner(Set<?> reads, Map<?, ?> transitiveGraph, Object owner) {
        for (Object dependency : reads) {
            Object value = transitiveGraph.get(dependency);
            if (value instanceof Set<?> transitive && transitive.contains(owner)) return true;
        }
        return false;
    }

    public static boolean oculusValidPathChar(char value) {
        return value < OCULUS_PATH_CHARS.length && OCULUS_PATH_CHARS[value];
    }

    public static boolean oculusValidPath(String value) {
        if ("DUMMY".equals(value)) return false;
        for (int index = 0; index < value.length(); index++) {
            if (!oculusValidPathChar(value.charAt(index))) return false;
        }
        return true;
    }

    private static boolean[] createOculusPathChars() {
        boolean[] valid = new boolean[128];
        for (char value = '0'; value <= '9'; value++) valid[value] = true;
        for (char value = 'a'; value <= 'z'; value++) valid[value] = true;
        for (char value = 'A'; value <= 'Z'; value++) valid[value] = true;
        valid['_'] = true;
        valid['-'] = true;
        valid['/'] = true;
        valid['.'] = true;
        return valid;
    }

    public static boolean containsClassTarget(Map<?, ?> accessTransformers, Object asmType) {
        reportInvoked();
        try {
            return index(accessTransformers).byClass().containsKey(asmType);
        } catch (Throwable throwable) {
            reportFallback(throwable);
            return scanContains(accessTransformers, asmType);
        }
    }

    public static Map<?, ?> getTransformersForTarget(Map<?, ?> accessTransformers, Object asmType) {
        reportInvoked();
        try {
            Map<?, ?> transformers = index(accessTransformers).byClass().get(asmType);
            return transformers == null ? Map.of() : transformers;
        } catch (Throwable throwable) {
            reportFallback(throwable);
            return scanTransformers(accessTransformers, asmType);
        }
    }

    public static void invalidate(Map<?, ?> accessTransformers) {
        INDEXES.remove(accessTransformers);
    }

    public static String opcodeName(Class<?> constants, int opcode, String start, int minimum) {
        if (opcode >= minimum) {
            try {
                String[] names = OPCODE_NAMES.get(constants)
                        .computeIfAbsent(start, ignored -> collectIntegerConstants(constants, start));
                String name = opcode < names.length ? names[opcode] : null;
                if (name != null) return name;
            } catch (Throwable ignored) {
            }
        }
        return opcode < 0 ? "UNKNOWN" : Integer.toString(opcode);
    }

    public static Iterator<?> packageConfigIterator(List<?> configs, String className) {
        try {
            Object owner = mixinIndex(configs).packageOwner(className);
            return owner == null ? Collections.emptyIterator() : Collections.singleton(owner).iterator();
        } catch (Throwable throwable) {
            reportMixinIndexFailure(throwable);
            return configs.iterator();
        }
    }

    public static Iterator<?> targetConfigIterator(List<?> configs, String className) {
        try {
            List<?> candidates = mixinIndex(configs).targetConfigs().get(className);
            return candidates == null ? Collections.emptyIterator() : candidates.iterator();
        } catch (Throwable throwable) {
            reportMixinIndexFailure(throwable);
            return configs.iterator();
        }
    }

    public static void applyIndexedObjectHolders(Set<?> handlers, Predicate<?> filter) {
        Object registry = predicateRegistry(filter);
        if (registry == null) {
            applyObjectHolderHandlers(handlers, filter);
            return;
        }
        Iterable<?> work;
        try {
            ObjectHolderIndex index = objectHolderIndex(handlers);
            List<Object> selected = index.byRegistry().get(registry);
            int selectedSize = selected == null ? 0 : selected.size();
            List<Object> candidates = new ArrayList<>(selectedSize + index.generic().size());
            if (selected != null) candidates.addAll(selected);
            candidates.addAll(index.generic());
            work = candidates;
            System.setProperty("lightload.runtimePatch.objectHolders", "indexed");
            System.setProperty("lightload.runtimePatch.objectHolderCandidates", Integer.toString(candidates.size()));
            System.setProperty("lightload.runtimePatch.objectHolderTotal", Integer.toString(handlers.size()));
        } catch (Throwable throwable) {
            System.setProperty("lightload.runtimePatch.objectHolders", "fallback");
            work = handlers;
        }
        applyObjectHolderHandlers(work, filter);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void applyObjectHolderHandlers(Iterable<?> handlers, Predicate<?> filter) {
        RuntimeException aggregate = new RuntimeException(
                "Failed to apply some object holders, see suppressed exceptions for details");
        for (Object handler : handlers) {
            try {
                ((Consumer) handler).accept(filter);
            } catch (Exception exception) {
                aggregate.addSuppressed(exception);
            }
        }
        if (aggregate.getSuppressed().length > 0) throw aggregate;
    }

    private static ObjectHolderIndex objectHolderIndex(Set<?> handlers) {
        synchronized (OBJECT_HOLDER_INDEXES) {
            ObjectHolderIndex existing = OBJECT_HOLDER_INDEXES.get(handlers);
            if (existing != null && existing.sourceSize() == handlers.size()) return existing;
            Map<Object, List<Object>> byRegistry = new HashMap<>();
            List<Object> generic = new ArrayList<>();
            for (Object handler : handlers) {
                Object registry = handlerRegistry(handler);
                if (registry == null) generic.add(handler);
                else byRegistry.computeIfAbsent(registry, ignored -> new ArrayList<>()).add(handler);
            }
            Map<Object, List<Object>> immutable = new HashMap<>(byRegistry.size());
            byRegistry.forEach((registry, values) -> immutable.put(registry, List.copyOf(values)));
            ObjectHolderIndex created = new ObjectHolderIndex(handlers.size(), Map.copyOf(immutable), List.copyOf(generic));
            OBJECT_HOLDER_INDEXES.put(handlers, created);
            System.setProperty("lightload.runtimePatch.objectHolderIndexed",
                    Integer.toString(handlers.size() - generic.size()));
            System.setProperty("lightload.runtimePatch.objectHolderGeneric", Integer.toString(generic.size()));
            return created;
        }
    }

    private static Object predicateRegistry(Predicate<?> predicate) {
        String name = predicate.getClass().getName();
        if (!name.startsWith("net.neoforged.registries.GameData$$Lambda")) return null;
        return capturedResourceLocation(predicate, false);
    }

    private static Object handlerRegistry(Object handler) {
        String name = handler.getClass().getName();
        if (name.equals("net.neoforged.registries.ObjectHolderRef")) {
            Object registry = declaredFieldValue(handler, "registry");
            return registry == null ? null : registryName(registry);
        }
        if (name.equals("net.neoforged.registries.RegistryObject$1")) {
            Object registryName = declaredFieldValue(handler, "val$registryName");
            return isResourceLocation(registryName) ? registryName : null;
        }
        if (name.startsWith("net.neoforged.registries.RegistryObject$$Lambda")) {
            return capturedResourceLocation(handler, true);
        }
        return null;
    }

    private static Object capturedResourceLocation(Object owner, boolean resolveRegistry) {
        for (Class<?> type = owner.getClass(); type != null; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || !field.trySetAccessible()) continue;
                try {
                    Object value = field.get(owner);
                    if (isResourceLocation(value)) return value;
                    if (resolveRegistry && value != null) {
                        Object registry = registryName(value);
                        if (registry != null) return registry;
                    }
                } catch (IllegalAccessException ignored) {
                }
            }
        }
        return null;
    }

    private static Object declaredFieldValue(Object owner, String name) {
        try {
            Field field = owner.getClass().getDeclaredField(name);
            if (!field.trySetAccessible()) return null;
            return field.get(owner);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static Object registryName(Object registry) {
        try {
            Method method = inheritedPublicMethod(registry.getClass(), "getRegistryName", 0);
            Object value = method.invoke(registry);
            return isResourceLocation(value) ? value : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean isResourceLocation(Object value) {
        return value != null && value.getClass().getName().equals("net.minecraft.resources.ResourceLocation");
    }

    private static Index index(Map<?, ?> accessTransformers) throws Exception {
        Index current = INDEXES.get(accessTransformers);
        int size = accessTransformers.size();
        if (current != null && current.sourceSize() == size) return current;
        synchronized (accessTransformers) {
            current = INDEXES.get(accessTransformers);
            size = accessTransformers.size();
            if (current != null && current.sourceSize() == size) return current;
            Map<Object, Map<Object, Map<String, Object>>> byClass = new HashMap<>();
            for (Object transformer : accessTransformers.values()) {
                Object target = GET_TARGET.get(transformer.getClass()).invoke(transformer);
                TargetMethods methods = TARGET_METHODS.get(target.getClass());
                Object classType = methods.asmType().invoke(target);
                Object targetType = methods.targetType().invoke(target);
                String targetName = String.valueOf(methods.targetName().invoke(target));
                byClass.computeIfAbsent(classType, ignored -> new HashMap<>())
                        .computeIfAbsent(targetType, ignored -> new HashMap<>())
                        .put(targetName, transformer);
            }
            Map<Object, Map<?, ?>> immutable = new HashMap<>();
            byClass.forEach((classType, groups) -> {
                Map<Object, Map<String, Object>> immutableGroups = new HashMap<>();
                groups.forEach((targetType, transformers) ->
                        immutableGroups.put(targetType, Map.copyOf(transformers)));
                immutable.put(classType, Map.copyOf(immutableGroups));
            });
            Index created = new Index(size, Map.copyOf(immutable));
            INDEXES.put(accessTransformers, created);
            System.setProperty("lightload.runtimePatch.indexSize", Integer.toString(size));
            return created;
        }
    }

    private static MixinConfigIndex mixinIndex(List<?> configs) throws Exception {
        synchronized (MIXIN_CONFIG_INDEXES) {
            MixinConfigIndex current = MIXIN_CONFIG_INDEXES.get(configs);
            if (current != null && current.matches(configs)) return current;

            Map<String, List<Object>> targets = new HashMap<>();
            Map<String, Object> packages = new HashMap<>();
            for (Object config : configs) {
                MixinConfigMethods methods = MIXIN_CONFIG_METHODS.get(config.getClass());
                String mixinPackage = String.valueOf(methods.mixinPackage().invoke(config));
                if (!mixinPackage.isEmpty()) packages.putIfAbsent(mixinPackage, config);
                Object targetSet = methods.targets().invoke(config);
                if (!(targetSet instanceof Set<?> set)) {
                    throw new IllegalStateException("Mixin targets are not a Set on " + config.getClass().getName());
                }
                for (Object target : set) {
                    targets.computeIfAbsent(String.valueOf(target), ignored -> new ArrayList<>()).add(config);
                }
            }
            Map<String, List<Object>> immutableTargets = new HashMap<>();
            targets.forEach((target, candidates) -> immutableTargets.put(target, List.copyOf(candidates)));
            MixinConfigIndex created = new MixinConfigIndex(configs.size(), first(configs), last(configs),
                    Map.copyOf(immutableTargets), Map.copyOf(packages));
            MIXIN_CONFIG_INDEXES.put(configs, created);
            System.setProperty("lightload.runtimePatch.mixinTargetConfigs", Integer.toString(configs.size()));
            System.setProperty("lightload.runtimePatch.mixinTargetClasses",
                    Integer.toString(immutableTargets.size()));
            return created;
        }
    }

    private static boolean scanContains(Map<?, ?> accessTransformers, Object asmType) {
        try {
            for (Object target : accessTransformers.keySet()) {
                Object candidate = TARGET_METHODS.get(target.getClass()).asmType().invoke(target);
                if (asmType.equals(candidate)) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    private static Map<?, ?> scanTransformers(Map<?, ?> accessTransformers, Object asmType) {
        Map<Object, Map<String, Object>> result = new HashMap<>();
        try {
            for (Map.Entry<?, ?> entry : accessTransformers.entrySet()) {
                Object target = entry.getKey();
                TargetMethods methods = TARGET_METHODS.get(target.getClass());
                if (!asmType.equals(methods.asmType().invoke(target))) continue;
                Object targetType = methods.targetType().invoke(target);
                String targetName = String.valueOf(methods.targetName().invoke(target));
                result.computeIfAbsent(targetType, ignored -> new HashMap<>()).put(targetName, entry.getValue());
            }
        } catch (Throwable ignored) {
        }
        return result;
    }

    private static String modulePackageKey(Object secureJar, Object metadata) throws Exception {
        SecureJarMethods jarMethods = SECURE_JAR_METHODS.get(secureJar.getClass());
        Object value = jarMethods.primaryPath().invoke(secureJar);
        if (!(value instanceof Path path)
                || path.getFileSystem() != Path.of(".").getFileSystem()
                || path.toString().isBlank()
                || !Files.isRegularFile(path)) return null;
        ModuleMetadataMethods metadataMethods = MODULE_METADATA_METHODS.get(metadata.getClass());
        String name = String.valueOf(metadataMethods.name().invoke(metadata));
        String version = String.valueOf(metadataMethods.version().invoke(metadata));
        Path normalized = path.toAbsolutePath().normalize();
        return normalized + "\n" + Files.size(normalized) + '\n'
                + Files.getLastModifiedTime(normalized).toMillis() + '\n' + name + '\n' + version;
    }

    private static void scheduleModulePackageWrite() {
        Path path = modulePackageIndexPath;
        if (path == null) return;
        synchronized (MODULE_PACKAGES) {
            if (modulePackageWrite != null) modulePackageWrite.cancel(false);
            modulePackageWrite = MODULE_CACHE_WRITER.schedule(RuntimeTransformationHooks::persistModulePackages,
                    750, TimeUnit.MILLISECONDS);
        }
    }

    private static void persistModulePackages() {
        Path path = modulePackageIndexPath;
        if (path == null) return;
        try {
            ModulePackageIndexStore.write(path, Map.copyOf(MODULE_PACKAGES));
            System.setProperty("lightload.runtimePatch.modulePackages", "persisted");
            System.setProperty("lightload.runtimePatch.modulePackageEntries",
                    Integer.toString(MODULE_PACKAGES.size()));
        } catch (Throwable throwable) {
            System.setProperty("lightload.runtimePatch.modulePackages", "persist-failed");
        }
    }

    private static void publishModulePackageStats() {
        System.setProperty("lightload.runtimePatch.modulePackageHits", Long.toString(MODULE_PACKAGE_HITS.sum()));
        System.setProperty("lightload.runtimePatch.modulePackageMisses", Long.toString(MODULE_PACKAGE_MISSES.sum()));
    }

    private static Method publicMethod(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            if (!Modifier.isPublic(current.getModifiers())) continue;
            try {
                Method method = current.getDeclaredMethod(name);
                if (Modifier.isPublic(method.getModifiers())) return method;
            } catch (NoSuchMethodException ignored) {
            }
        }
        throw new IllegalStateException("No public method " + name + " on " + type.getName());
    }

    private static Method inheritedPublicMethod(Class<?> type, String name, int parameters) {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == parameters
                    && Modifier.isPublic(method.getModifiers())) return method;
        }
        throw new IllegalStateException("No public method " + name + " on " + type.getName());
    }

    private static void reportInvoked() {
        if (invoked) return;
        invoked = true;
        System.setProperty("lightload.runtimePatch.invoked", "true");
    }

    private static void reportFallback(Throwable throwable) {
        if (fallbackReported) return;
        fallbackReported = true;
        System.setProperty("lightload.runtimePatch.fallback", throwable.getClass().getName()
                + (throwable.getMessage() == null ? "" : ": " + throwable.getMessage()));
    }

    private static void reportMixinIndexFailure(Throwable throwable) {
        System.setProperty("lightload.runtimePatch.mixinTargets", "fallback");
        if (!MIXIN_INDEX_FAILURE_REPORTED.compareAndSet(false, true)) return;
        System.setProperty("lightload.runtimePatch.mixinTargetFailure", throwable.getClass().getName()
                + (throwable.getMessage() == null ? "" : ": " + throwable.getMessage()));
    }

    private static String[] collectIntegerConstants(Class<?> constants, String start) {
        Map<Integer, String> names = new HashMap<>();
        boolean collecting = false;
        try {
            for (var field : constants.getDeclaredFields()) {
                if (!collecting && field.getName().equals(start)) collecting = true;
                if (!collecting || field.getType() != int.class) continue;
                names.putIfAbsent(field.getInt(null), field.getName());
            }
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
        int maximum = names.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1);
        String[] indexed = new String[Math.max(0, maximum + 1)];
        names.forEach((opcode, name) -> {
            if (opcode >= 0) indexed[opcode] = name;
        });
        return indexed;
    }

    private static Method accessibleMethod(Class<?> type, String name) {
        try {
            Method method = type.getDeclaredMethod(name);
            if (!method.trySetAccessible()) {
                throw new IllegalStateException("Cannot access " + type.getName() + "." + name);
            }
            return method;
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("No method " + name + " on " + type.getName(), exception);
        }
    }

    private static Object first(List<?> values) {
        return values.isEmpty() ? null : values.get(0);
    }

    private static Object last(List<?> values) {
        return values.isEmpty() ? null : values.get(values.size() - 1);
    }

    private record TargetMethods(Method asmType, Method targetType, Method targetName) {
    }

    private record MixinConfigMethods(Method mixinPackage, Method targets) {
    }

    private record SecureJarMethods(Method primaryPath, Method packages) {
    }

    private record ModuleMetadataMethods(Method name, Method version) {
    }

    private record ObjectHolderIndex(int sourceSize, Map<Object, List<Object>> byRegistry, List<Object> generic) {
    }

    private record MixinConfigIndex(int sourceSize, Object first, Object last,
                                    Map<String, List<Object>> targetConfigs,
                                    Map<String, Object> packageOwners) {
        private boolean matches(List<?> configs) {
            return sourceSize == configs.size()
                    && first == RuntimeTransformationHooks.first(configs)
                    && last == RuntimeTransformationHooks.last(configs);
        }

        private Object packageOwner(String className) {
            for (int separator = className.length(); separator > 0; ) {
                separator = className.lastIndexOf('.', separator - 1);
                if (separator < 0) break;
                Object owner = packageOwners.get(className.substring(0, separator + 1));
                if (owner != null) return owner;
            }
            return null;
        }
    }

    private record Index(int sourceSize, Map<Object, Map<?, ?>> byClass) {
    }
}

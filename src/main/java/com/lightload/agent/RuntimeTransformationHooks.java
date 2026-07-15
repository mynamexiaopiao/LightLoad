package com.lightload.agent;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.ArrayDeque;
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

/** Bootstrap-visible exact index for Forge access-transformer targets. */
public final class RuntimeTransformationHooks {
    private static final boolean[] OCULUS_PATH_CHARS = createOculusPathChars();
    private static final Map<Map<?, ?>, Index> INDEXES = Collections.synchronizedMap(new IdentityHashMap<>());
    private static final Map<List<?>, MixinConfigIndex> MIXIN_CONFIG_INDEXES =
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
    private static volatile boolean invoked;
    private static volatile boolean fallbackReported;
    private static final ClassValue<Map<String, Map<Integer, String>>> OPCODE_NAMES = new ClassValue<>() {
        @Override
        protected Map<String, Map<Integer, String>> computeValue(Class<?> type) {
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
                String name = OPCODE_NAMES.get(constants)
                        .computeIfAbsent(start, ignored -> collectIntegerConstants(constants, start))
                        .get(opcode);
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

    private static Map<Integer, String> collectIntegerConstants(Class<?> constants, String start) {
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
        return Map.copyOf(names);
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

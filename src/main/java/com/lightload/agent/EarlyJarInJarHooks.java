package com.lightload.agent;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public final class EarlyJarInJarHooks {
    private static final String METADATA_PATH = "META-INF/jarjar/metadata.json";
    private static final AtomicInteger THREAD_ID = new AtomicInteger();
    private static final ThreadLocal<ScanSession> SESSION = new ThreadLocal<>();
    private static volatile Path indexPath;
    private static volatile int threads = 4;
    private static volatile boolean parallelMetadata = true;
    private static volatile String targetFingerprint = "unknown";

    private EarlyJarInJarHooks() {
    }

    public static void configure(Path path, int workerCount, boolean parallel) {
        indexPath = path;
        threads = workerCount;
        parallelMetadata = parallel;
    }

    public static void setTargetFingerprint(String fingerprint) {
        targetFingerprint = fingerprint;
    }

    public static Object tryLoad(Object locator, Iterable<?> loadedMods) {
        List<ParentFingerprint> parents = null;
        try {
            List<Object> mods = toList(loadedMods);
            parents = fingerprint(mods);
            Path path = indexPath;
            if (path != null && Files.isRegularFile(path)) {
                JarJarIndex index = JarJarIndexStore.read(path);
                if (!targetFingerprint.equals(index.targetFingerprint())) {
                    reportMiss("locator-bytecode-changed");
                } else {
                    int mismatch = firstMismatch(parents, index.parents());
                    if (mismatch >= 0) {
                        reportMiss("parent-" + mismatch + "-changed");
                    } else {
                        Object cached = reconstruct(locator, mods, index.dependencies());
                        System.setProperty("lightload.agent.cache", "hit");
                        System.clearProperty("lightload.agent.cache.reason");
                        System.out.println("[Lightload Agent] Reused " + index.dependencies().size() + " Jar-in-Jar selections");
                        return cached;
                    }
                }
            }
            SESSION.set(new ScanSession(mods, parents));
            if (parallelMetadata) prefetchMetadata(parents);
            System.setProperty("lightload.agent.cache", "miss");
        } catch (Throwable throwable) {
            try {
                if (indexPath != null) Files.deleteIfExists(indexPath);
                List<Object> mods = toList(loadedMods);
                if (parents == null) parents = fingerprint(mods);
                SESSION.set(new ScanSession(mods, parents));
                if (parallelMetadata) prefetchMetadata(parents);
            } catch (Throwable recoveryFailure) {
                SESSION.remove();
            }
            System.setProperty("lightload.agent.cache", "fallback");
            System.setProperty("lightload.agent.cache.reason", describe(throwable));
            System.err.println("[Lightload Agent] Jar-in-Jar cache lookup failed; using Forge: " + throwable);
        }
        return null;
    }

    public static void recordLoaded(Object parent, Object nestedPath, Object optionalResult) {
        ScanSession session = SESSION.get();
        if (session == null || !(optionalResult instanceof Optional<?> optional) || optional.isEmpty()) return;
        try {
            LoadedDependency parentDependency = session.loaded.get(parent);
            Integer parentIndex = parentDependency == null ? session.parentIndex(parent) : parentDependency.parentIndex;
            if (parentIndex == null) {
                System.setProperty("lightload.agent.cache.recordReason", "unmatched-parent");
                return;
            }
            List<String> nestedPaths = parentDependency == null
                    ? List.of(nestedPath.toString())
                    : appendPath(parentDependency.nestedPaths, nestedPath.toString());
            session.loaded.put(optional.get(), new LoadedDependency(parentIndex, nestedPaths));
        } catch (Throwable ignored) {
        }
    }

    public static void recordResult(Object locator, Iterable<?> loadedMods, List<?> dependencies) {
        ScanSession session = SESSION.get();
        SESSION.remove();
        if (session == null || indexPath == null) return;
        try {
            List<JarJarIndex.Dependency> stored = new ArrayList<>(dependencies.size());
            for (Object dependency : dependencies) {
                LoadedDependency loaded = session.loaded.get(dependency);
                if (loaded == null) loaded = session.loadedByIdentity(locator, dependency);
                if (loaded == null) {
                    Integer sourceIndex = session.parentIndex(dependency);
                    if (sourceIndex == null) sourceIndex = session.sourceIndexByIdentity(locator, dependency);
                    if (sourceIndex == null) sourceIndex = uniqueSourceIndexByFileIdentity(session.mods, dependency);
                    if (sourceIndex == null) {
                        String detail = dependencyDetail(locator, dependency);
                        System.setProperty("lightload.agent.cache.recordReason",
                                "unmatched-dependency-" + stored.size() + '-' + detail);
                        System.out.println("[Lightload Agent] Jar-in-Jar selections were not persisted: unmatched dependency "
                                + stored.size() + " (" + detail + ')');
                        return;
                    }
                    stored.add(new JarJarIndex.Dependency(sourceIndex, List.of(),
                            identify(locator, session.mods.get(sourceIndex))));
                    continue;
                }
                stored.add(new JarJarIndex.Dependency(loaded.parentIndex, loaded.nestedPaths, identify(locator, dependency)));
            }
            JarJarIndexStore.write(indexPath, new JarJarIndex(targetFingerprint, session.parents, stored));
            System.out.println("[Lightload Agent] Recorded " + stored.size() + " Jar-in-Jar selections");
        } catch (Throwable throwable) {
            System.err.println("[Lightload Agent] Could not persist Jar-in-Jar selections: " + throwable);
        }
    }

    private static Object reconstruct(Object locator, List<Object> mods,
                                      List<JarJarIndex.Dependency> dependencies) throws Exception {
        List<Object> loaded = new ArrayList<>(dependencies.size());
        try {
            Method load = findMethod(locator.getClass(), "loadModFileFrom", 2);
            load.setAccessible(true);
            for (int index = 0; index < dependencies.size(); index++) {
                JarJarIndex.Dependency dependency = dependencies.get(index);
                if (dependency.parentIndex() < 0 || dependency.parentIndex() >= mods.size()) {
                    throw new IllegalStateException("dependency-" + index + " has invalid parent index "
                            + dependency.parentIndex());
                }
                Object parent = mods.get(dependency.parentIndex());
                Object mod;
                mod = parent;
                for (String nestedPath : dependency.nestedPaths()) {
                    Object value = load.invoke(locator, mod, Path.of(nestedPath));
                    if (!(value instanceof Optional<?> optional) || optional.isEmpty()) {
                        throw new IllegalStateException("dependency-" + index + " path " + nestedPath
                                + " could not be loaded");
                    }
                    mod = optional.get();
                    loaded.add(mod);
                }
                String identity = identify(locator, mod);
                if (!dependency.identity().equals(identity)) {
                    throw new IllegalStateException("dependency-" + index + " identity changed from "
                            + dependency.identity() + " to " + identity);
                }
            }
            return loaded;
        } catch (Throwable throwable) {
            closeLoaded(loaded);
            reportMiss("reconstruct-" + describe(throwable));
            if (throwable instanceof Exception exception) throw exception;
            throw new IllegalStateException(throwable);
        }
    }

    private static String identify(Object locator, Object mod) throws Exception {
        Method identify = findMethod(locator.getClass(), "identifyMod", 1);
        identify.setAccessible(true);
        return String.valueOf(identify.invoke(locator, mod));
    }

    private static Method findMethod(Class<?> type, String name, int parameters) throws NoSuchMethodException {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == parameters) return method;
            }
        }
        throw new NoSuchMethodException(name);
    }

    private static List<Object> toList(Iterable<?> values) {
        List<Object> result = new ArrayList<>();
        values.forEach(result::add);
        return result;
    }

    private static List<String> appendPath(List<String> parentPaths, String nestedPath) {
        List<String> result = new ArrayList<>(parentPaths.size() + 1);
        result.addAll(parentPaths);
        result.add(nestedPath);
        return List.copyOf(result);
    }

    private static List<ParentFingerprint> fingerprint(List<Object> mods) throws Exception {
        List<ParentFingerprint> result = new ArrayList<>(mods.size());
        for (int index = 0; index < mods.size(); index++) {
            Object mod = mods.get(index);
            Path path = physicalPath(mod);
            boolean regularFile = Files.isRegularFile(path);
            long size = regularFile ? Files.size(path) : -1;
            long modified = regularFile ? Files.getLastModifiedTime(path).toMillis() : -1;
            boolean physical = path.getFileSystem() == Path.of(".").getFileSystem()
                    && !path.toString().isBlank();
            String identity = physical ? path.toString() : virtualIdentity(mod, index);
            result.add(new ParentFingerprint(identity, size, modified));
        }
        return List.copyOf(result);
    }

    private static Object secureJar(Object mod) throws Exception {
        Method secureJarMethod = findMethod(mod.getClass(), "getSecureJar", 0);
        secureJarMethod.setAccessible(true);
        return secureJarMethod.invoke(mod);
    }

    private static Path physicalPath(Object mod) throws Exception {
        Object secureJar = secureJar(mod);
        Method primaryPath = findMethod(secureJar.getClass(), "getPrimaryPath", 0);
        primaryPath.setAccessible(true);
        return ((Path) primaryPath.invoke(secureJar)).toAbsolutePath().normalize();
    }

    private static String virtualIdentity(Object mod, int index) {
        return "virtual:" + index + ':' + virtualIdentityBase(mod);
    }

    private static String virtualIdentityBase(Object mod) {
        String name = invokeString(mod, "getFileName");
        String type = invokeString(mod, "getType");
        return mod.getClass().getName() + ':' + type + ':' + name;
    }

    private static String invokeString(Object target, String methodName) {
        try {
            Method method = findMethod(target.getClass(), methodName, 0);
            method.setAccessible(true);
            return String.valueOf(method.invoke(target));
        } catch (Throwable ignored) {
            return "unknown";
        }
    }

    static Integer uniqueSourceIndexByFileIdentity(List<?> mods, Object dependency) {
        String fileName = invokeString(dependency, "getFileName");
        String type = invokeString(dependency, "getType");
        if (fileName.isBlank() || "unknown".equals(fileName) || "unknown".equals(type)) return null;
        String className = dependency.getClass().getName();
        Integer match = null;
        for (int index = 0; index < mods.size(); index++) {
            Object candidate = mods.get(index);
            if (!className.equals(candidate.getClass().getName())
                    || !fileName.equals(invokeString(candidate, "getFileName"))
                    || !type.equals(invokeString(candidate, "getType"))) continue;
            if (match != null) return null;
            match = index;
        }
        return match;
    }

    private static String dependencyDetail(Object locator, Object dependency) {
        StringBuilder detail = new StringBuilder();
        try {
            detail.append(identify(locator, dependency));
        } catch (Throwable ignored) {
            detail.append("unknown-id");
        }
        detail.append(':').append(dependency.getClass().getName());
        detail.append(':').append(invokeString(dependency, "getFileName"));
        try {
            detail.append(':').append(physicalPath(dependency));
        } catch (Throwable throwable) {
            detail.append(":no-path-").append(describe(throwable));
        }
        return detail.toString().replace('\n', '_').replace('\r', '_');
    }

    private static void prefetchMetadata(List<ParentFingerprint> parents) {
        if (parents.size() < 8) return;
        ExecutorService executor = Executors.newFixedThreadPool(Math.min(threads, parents.size()), runnable -> {
            Thread thread = new Thread(runnable, "Lightload-JarJar-" + THREAD_ID.incrementAndGet());
            thread.setDaemon(true);
            thread.setPriority(Math.max(Thread.MIN_PRIORITY, Thread.NORM_PRIORITY - 1));
            return thread;
        });
        try {
            CompletableFuture<?>[] futures = parents.stream().map(parent -> CompletableFuture.runAsync(() -> {
                if (parent.path().startsWith("virtual:")) return;
                Path path = Path.of(parent.path());
                if (!Files.isRegularFile(path)) return;
                try (JarFile jar = new JarFile(path.toFile(), false)) {
                    JarEntry entry = jar.getJarEntry(METADATA_PATH);
                    if (entry != null) try (InputStream input = jar.getInputStream(entry)) {
                        input.transferTo(OutputSink.INSTANCE);
                    }
                } catch (Exception ignored) {
                }
            }, executor)).toArray(CompletableFuture[]::new);
            CompletableFuture.allOf(futures).join();
        } finally {
            executor.shutdown();
        }
    }

    private static void closeLoaded(List<Object> loaded) {
        for (Object mod : loaded) {
            try {
                Method method = findMethod(mod.getClass(), "getFilePath", 0);
                method.setAccessible(true);
                FileSystem fileSystem = ((Path) method.invoke(mod)).getFileSystem();
                if (fileSystem != Path.of(".").getFileSystem() && fileSystem.isOpen()) fileSystem.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static int firstMismatch(List<ParentFingerprint> current, List<ParentFingerprint> stored) {
        if (current.size() != stored.size()) return Math.min(current.size(), stored.size());
        for (int index = 0; index < current.size(); index++) {
            if (!current.get(index).equals(stored.get(index))) return index;
        }
        return -1;
    }

    private static void reportMiss(String reason) {
        System.setProperty("lightload.agent.cache.reason", reason);
        System.out.println("[Lightload Agent] Jar-in-Jar cache miss: " + reason);
    }

    private static String describe(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        String message = current.getMessage();
        return current.getClass().getSimpleName() + (message == null || message.isBlank() ? "" : '-' + message);
    }

    private static final class ScanSession {
        private final List<Object> mods;
        private final List<ParentFingerprint> parents;
        private final Map<Object, Integer> parentIndexes = Collections.synchronizedMap(new IdentityHashMap<>());
        private final Map<Object, Integer> secureJarIndexes = Collections.synchronizedMap(new IdentityHashMap<>());
        private final Map<String, Integer> physicalPathIndexes = new java.util.HashMap<>();
        private final Map<String, Integer> virtualIndexes = new java.util.HashMap<>();
        private final Map<Object, LoadedDependency> loaded = Collections.synchronizedMap(new IdentityHashMap<>());

        private ScanSession(List<Object> mods, List<ParentFingerprint> parents) {
            this.mods = List.copyOf(mods);
            this.parents = parents;
            for (int index = 0; index < mods.size(); index++) {
                Object mod = mods.get(index);
                parentIndexes.put(mod, index);
                try {
                    secureJarIndexes.put(secureJar(mod), index);
                    Path path = physicalPath(mod);
                    if (path.getFileSystem() == Path.of(".").getFileSystem() && !path.toString().isBlank()) {
                        physicalPathIndexes.putIfAbsent(path.toString(), index);
                    } else {
                        virtualIndexes.putIfAbsent(virtualIdentityBase(mod), index);
                    }
                } catch (Throwable ignored) {
                }
            }
        }

        private Integer parentIndex(Object parent) {
            Integer index = parentIndexes.get(parent);
            if (index != null) return index;
            try {
                index = secureJarIndexes.get(secureJar(parent));
                if (index != null) return index;
                Path path = physicalPath(parent);
                if (path.getFileSystem() == Path.of(".").getFileSystem() && !path.toString().isBlank()) {
                    return physicalPathIndexes.get(path.toString());
                }
                return virtualIndexes.get(virtualIdentityBase(parent));
            } catch (Throwable ignored) {
                return null;
            }
        }

        private LoadedDependency loadedByIdentity(Object locator, Object dependency) {
            String expected;
            try {
                expected = identify(locator, dependency);
            } catch (Throwable ignored) {
                return null;
            }
            synchronized (loaded) {
                for (Map.Entry<Object, LoadedDependency> entry : loaded.entrySet()) {
                    try {
                        if (expected.equals(identify(locator, entry.getKey()))) return entry.getValue();
                    } catch (Throwable ignored) {
                    }
                }
            }
            return null;
        }

        private Integer sourceIndexByIdentity(Object locator, Object dependency) {
            String expected;
            try {
                expected = identify(locator, dependency);
            } catch (Throwable ignored) {
                return null;
            }
            for (int index = 0; index < mods.size(); index++) {
                try {
                    if (expected.equals(identify(locator, mods.get(index)))) return index;
                } catch (Throwable ignored) {
                }
            }
            return null;
        }
    }

    private record LoadedDependency(int parentIndex, List<String> nestedPaths) {
    }

    private static final class OutputSink extends java.io.OutputStream {
        private static final OutputSink INSTANCE = new OutputSink();

        @Override
        public void write(int value) {
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
        }
    }
}

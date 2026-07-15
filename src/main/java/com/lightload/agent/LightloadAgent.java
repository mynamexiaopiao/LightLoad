package com.lightload.agent;

import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.jar.JarFile;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

public final class LightloadAgent {
    private static JarFile bootstrapJar;
    private LightloadAgent() {
    }

    public static void premain(String arguments, Instrumentation instrumentation) {
        if (Boolean.getBoolean("lightload.agent.disable") || Boolean.getBoolean("lightload.disable")) return;
        if (helperProcess()) {
            System.setProperty("lightload.agent.cache", "helper-skipped");
            return;
        }
        try {
            Path gameDirectory = resolveGameDirectory(arguments);
            System.setProperty("lightload.agent.gameDir", gameDirectory.toString());
            Properties config = loadConfig(gameDirectory.resolve("config").resolve("lightload.properties"));
            if (!Boolean.parseBoolean(config.getProperty("enabled", "true"))) return;
            if (!Boolean.parseBoolean(config.getProperty("earlyAgent", "true"))) return;
            configureRuntimeFlags(config);
            Path cacheDirectory = gameDirectory.resolve("cache").resolve("lightload").resolve("early");
            Path lock = cacheDirectory.resolve("early-agent.lock");
            if (Files.isRegularFile(lock)) {
                Files.deleteIfExists(lock);
                System.setProperty("lightload.agent.cache", "recovery");
                System.out.println("[Lightload Agent] Previous early startup did not complete; bypassing once");
                return;
            }
            Files.createDirectories(cacheDirectory);
            Files.writeString(lock, Long.toString(System.currentTimeMillis()));
            int threads = parseThreads(config.getProperty("jarJarThreads", "4"));
            boolean parallel = Boolean.parseBoolean(config.getProperty("parallelJarJarMetadata", "true"));
            installBootstrapHooks(instrumentation, arguments, cacheDirectory);
            invokeBootstrap("configure", new Class[]{Path.class, int.class, boolean.class},
                    cacheDirectory.resolve("jij-index-v1.bin"), threads, parallel);
             instrumentation.addTransformer(new JarInJarTransformer(instrumentation), false);
            installRuntimePatches(instrumentation);
            System.setProperty("lightload.agent.active", "true");
            System.setProperty("lightload.agent.cache", "waiting");
            System.out.println("[Lightload Agent] Early Forge optimization active");
        } catch (Throwable throwable) {
            System.setProperty("lightload.agent.cache", "failed");
            System.err.println("[Lightload Agent] Initialization failed; Forge will continue normally: " + throwable);
        }
    }

    public static void agentmain(String arguments, Instrumentation instrumentation) {
        if (Boolean.getBoolean("lightload.agent.disable") || Boolean.getBoolean("lightload.disable")
                || Boolean.getBoolean("lightload.runtimeAgent.active")) return;
        if (helperProcess()) return;
        String stage = "resolve-game-directory";
        try {
            Path gameDirectory = resolveGameDirectory(arguments);
            Properties config = loadConfig(gameDirectory.resolve("config").resolve("lightload.properties"));
            configureRuntimeFlags(config);
            stage = "create-cache-directory";
            Path cacheDirectory = gameDirectory.resolve("cache").resolve("lightload").resolve("early");
            Files.createDirectories(cacheDirectory);
            stage = "install-bootstrap-hooks";
            installBootstrapHooks(instrumentation, arguments, cacheDirectory);
            stage = "install-runtime-patches";
            installRuntimePatches(instrumentation);
            System.setProperty("lightload.runtimeAgent.active", "true");
            System.clearProperty("lightload.runtimeAgent.detail");
            System.clearProperty("lightload.runtimeAgent.stage");
            System.out.println("[Lightload Agent] Runtime Forge transformer optimization active");
        } catch (Throwable throwable) {
            System.setProperty("lightload.runtimeAgent.status", "failed");
            System.setProperty("lightload.runtimeAgent.stage", stage);
            System.setProperty("lightload.runtimeAgent.detail", describe(throwable));
            System.err.println("[Lightload Agent] Runtime initialization failed; Forge will continue normally: "
                    + describe(throwable));
        }
    }

    private static String describe(Throwable throwable) {
        StringBuilder description = new StringBuilder();
        Throwable current = throwable;
        for (int depth = 0; current != null && depth < 4; depth++, current = current.getCause()) {
            if (depth > 0) description.append(" <- ");
            description.append(current.getClass().getName());
            String message = current.getMessage();
            if (message != null && !message.isBlank()) description.append(": ").append(message);
        }
        StackTraceElement[] trace = throwable.getStackTrace();
        for (int index = 0; index < Math.min(6, trace.length); index++) {
            description.append(" @ ").append(trace[index]);
        }
        return description.toString();
    }

    private static boolean helperProcess() {
        String command = System.getProperty("sun.java.command", "").toLowerCase(java.util.Locale.ROOT);
        return command.contains("crashassistant")
                || command.contains("crash_assistant")
                || command.contains("crash-assistant");
    }

    private static Properties loadConfig(Path path) {
        Properties properties = new Properties();
        if (!Files.isRegularFile(path)) return properties;
        try (InputStream input = Files.newInputStream(path)) {
            properties.load(input);
        } catch (Exception ignored) {
        }
        return properties;
    }

    private static int parseThreads(String value) {
        try {
            return Math.max(1, Math.min(8, Integer.parseInt(value.trim())));
        } catch (RuntimeException ignored) {
            return 4;
        }
    }

    static void setTargetFingerprint(String fingerprint) throws Exception {
        invokeBootstrap("setTargetFingerprint", new Class[]{String.class}, fingerprint);
    }

    static Module hooksModule() throws ClassNotFoundException {
        return Class.forName("com.lightload.agent.EarlyJarInJarHooks", false, null).getModule();
    }

    private static void installBootstrapHooks(Instrumentation instrumentation, String arguments, Path cacheDirectory) throws Exception {
        String explicitJar = decodedOption(arguments, "agentJar");
        Path agentPath;
        if (explicitJar != null) {
            agentPath = Path.of(explicitJar).toAbsolutePath().normalize();
        } else {
            URI location = LightloadAgent.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            agentPath = Path.of(location);
        }
        Path hooksJar = cacheDirectory.resolve("bootstrap-hooks-v1.jar");
        createBootstrapHooksJar(agentPath, hooksJar);
        bootstrapJar = new JarFile(hooksJar.toFile(), false);
        instrumentation.appendToBootstrapClassLoaderSearch(bootstrapJar);
        Class.forName("com.lightload.agent.EarlyJarInJarHooks", true, null);
        Class<?> runtimeHooks = Class.forName("com.lightload.agent.RuntimeTransformationHooks", true, null);
        runtimeHooks.getMethod("configureModulePackageCache", Path.class)
                .invoke(null, cacheDirectory.resolve("module-packages-v1.bin"));
    }

    private static void installRuntimePatches(Instrumentation instrumentation) throws Exception {
        RuntimePatchTransformer transformer = new RuntimePatchTransformer(instrumentation);
        instrumentation.addTransformer(transformer, true);
        for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
            String name = loaded.getName().replace('.', '/');
            if ((!name.equals(RuntimePatchTransformer.ACCESS_TRANSFORMER_LIST)
                    && !name.equals(RuntimePatchTransformer.MIXIN_BYTECODE)
                    && !name.equals(RuntimePatchTransformer.MIXIN_PROCESSOR)
                    && !name.equals(RuntimePatchTransformer.RESOURCE_LOCATION)
                    && !name.equals(RuntimePatchTransformer.MOD_JAR_METADATA)
                    && !name.equals(RuntimePatchTransformer.JDK_MODULE_RESOLVER))
                    || !instrumentation.isModifiableClass(loaded)) continue;
            instrumentation.retransformClasses(loaded);
        }
    }

    private static void configureRuntimeFlags(Properties config) {
        if (System.getProperty("lightload.runtimePatch.mixinTargetIndex.enabled") == null) {
            System.setProperty("lightload.runtimePatch.mixinTargetIndex.enabled",
                    config.getProperty("mixinTargetIndex", "true"));
        }
        if (System.getProperty("lightload.runtimePatch.oculusPath.enabled") == null) {
            System.setProperty("lightload.runtimePatch.oculusPath.enabled",
                    config.getProperty("oculusPathValidationPatch", "true"));
        }
        if (System.getProperty("lightload.voxelShapeCache.enabled") == null) {
            System.setProperty("lightload.voxelShapeCache.enabled",
                    config.getProperty("voxelShapeCache", config.getProperty("aggressiveStartup", "true")));
        }
    }

    private static void createBootstrapHooksJar(Path agentPath, Path target) throws Exception {
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        String[] prefixes = {
                "com/lightload/agent/EarlyJarInJarHooks",
                 "com/lightload/agent/RuntimeTransformationHooks",
                "com/lightload/agent/ModulePackageIndexStore",
                "com/lightload/agent/ParentFingerprint",
                "com/lightload/agent/JarJarIndex",
                "com/lightload/agent/JarJarIndexStore"
        };
        try (JarFile source = new JarFile(agentPath.toFile(), false);
             JarOutputStream output = new JarOutputStream(Files.newOutputStream(temporary))) {
            var entries = source.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory() || !matches(entry.getName(), prefixes)) continue;
                output.putNextEntry(new JarEntry(entry.getName()));
                try (InputStream input = source.getInputStream(entry)) {
                    input.transferTo(output);
                }
                output.closeEntry();
            }
        }
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception unsupportedAtomicMove) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static boolean matches(String name, String[] prefixes) {
        for (String prefix : prefixes) if (name.startsWith(prefix)) return true;
        return false;
    }

    private static Object invokeBootstrap(String methodName, Class<?>[] parameterTypes, Object... arguments) throws Exception {
        Class<?> hooks = Class.forName("com.lightload.agent.EarlyJarInJarHooks", true, null);
        Method method = hooks.getMethod(methodName, parameterTypes);
        return method.invoke(null, arguments);
    }

    private static Path resolveGameDirectory(String arguments) {
        String explicitGameDirectory = decodedOption(arguments, "gameDir");
        if (explicitGameDirectory != null) return Path.of(explicitGameDirectory).toAbsolutePath().normalize();
        String command = System.getProperty("sun.java.command", "");
        Matcher matcher = Pattern.compile("(?:^|\\s)--gameDir\\s+(.+?)(?=\\s+--[A-Za-z]|$)").matcher(command);
        if (matcher.find()) return Path.of(matcher.group(1).replace("\\\"", "")).toAbsolutePath().normalize();
        return Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
    }

    private static String option(String arguments, String key) {
        if (arguments == null || arguments.isBlank()) return null;
        for (String part : arguments.split(";")) {
            int separator = part.indexOf('=');
            if (separator > 0 && part.substring(0, separator).trim().equals(key)) {
                return part.substring(separator + 1).trim();
            }
        }
        return null;
    }

    static String decodedOption(String arguments, String key) {
        String encoded = option(arguments, key + "64");
        if (encoded != null) {
            try {
                return new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException exception) {
                System.err.println("[Lightload Agent] Ignoring malformed " + key + "64 option");
            }
        }
        return option(arguments, key);
    }
}

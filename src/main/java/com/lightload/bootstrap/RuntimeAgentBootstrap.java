package com.lightload.bootstrap;

import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.jar.JarFile;

/** Installs the optional runtime transformer patch before mod classes fan out. */
public final class RuntimeAgentBootstrap {
    private static final AtomicBoolean STARTED = new AtomicBoolean();

    private RuntimeAgentBootstrap() {
    }

    public static void install() {
        if (!STARTED.compareAndSet(false, true) || Boolean.getBoolean("lightload.disable")
                || Boolean.getBoolean("lightload.runtimeAgent.disable")
                || Boolean.getBoolean("lightload.agent.active")) return;
        try {
            Path gameDirectory = resolveGameDirectory();
            if (!enabled(gameDirectory)) {
                System.setProperty("lightload.runtimeAgent.status", "disabled");
                return;
            }
            Path agentJar = resolveAgentJar(gameDirectory);
            if (agentJar == null) {
                System.setProperty("lightload.runtimeAgent.status", "agent-jar-not-found");
                return;
            }
            Path java = Path.of(System.getProperty("java.home"), "bin",
                    System.getProperty("os.name", "").startsWith("Windows") ? "java.exe" : "java");
            if (!Files.isRegularFile(java)) return;

            String options = "mode=runtime;gameDir64=" + encodeOption(gameDirectory)
                    + ";agentJar64=" + encodeOption(agentJar);
            Process process = new ProcessBuilder(
                    java.toString(), "--add-modules", "jdk.attach", "-cp", agentJar.toString(),
                    AgentAttacher.class.getName(), Long.toString(ProcessHandle.current().pid()),
                    agentJar.toString(), options)
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(Duration.ofSeconds(8).toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                process.destroy();
                process.waitFor(1, TimeUnit.SECONDS);
                if (process.isAlive()) process.destroyForcibly().waitFor(1, TimeUnit.SECONDS);
            }
            String output;
            try (InputStream input = process.getInputStream()) {
                output = new String(input.readAllBytes(), StandardCharsets.UTF_8).trim();
            }
            if (!finished) {
                System.setProperty("lightload.runtimeAgent.status", "timeout");
                System.err.println("[Lightload] Runtime agent attach timed out");
            } else if (process.exitValue() != 0) {
                System.setProperty("lightload.runtimeAgent.status", "attach-failed");
                System.err.println("[Lightload] Runtime agent attach failed: " + output);
            } else if (Boolean.getBoolean("lightload.runtimeAgent.active")) {
                String patch = System.getProperty("lightload.runtimeAgent.status", "active");
                System.out.println("[Lightload] Runtime transformer patch active: " + patch);
            } else {
                String status = System.getProperty("lightload.runtimeAgent.status");
                if (status == null || status.isBlank()) {
                    status = "attach-inactive";
                    System.setProperty("lightload.runtimeAgent.status", status);
                }
                String detail = System.getProperty("lightload.runtimeAgent.detail", output);
                System.err.println("[Lightload] Runtime agent returned without activating the patch: "
                        + status + (detail.isBlank() ? "" : " (" + detail + ")"));
            }
        } catch (Throwable throwable) {
            System.setProperty("lightload.runtimeAgent.status", "unavailable");
            System.err.println("[Lightload] Runtime transformer patch unavailable; using Forge defaults: " + throwable);
        }
    }

    private static boolean enabled(Path gameDirectory) {
        Properties properties = new Properties();
        Path path = gameDirectory.resolve("config").resolve("lightload.properties");
        if (Files.isRegularFile(path)) {
            try (InputStream input = Files.newInputStream(path)) {
                properties.load(input);
            } catch (Exception ignored) {
            }
        }
        if (System.getProperty("lightload.runtimePatch.mixinTargetIndex.enabled") == null) {
            System.setProperty("lightload.runtimePatch.mixinTargetIndex.enabled",
                    properties.getProperty("mixinTargetIndex", "true"));
        }
        if (System.getProperty("lightload.runtimePatch.oculusPath.enabled") == null) {
            System.setProperty("lightload.runtimePatch.oculusPath.enabled",
                    properties.getProperty("oculusPathValidationPatch", "true"));
        }
        return Boolean.parseBoolean(properties.getProperty("enabled", "true"))
                && Boolean.parseBoolean(properties.getProperty("runtimeTransformerPatches", "true"));
    }

    private static String encodeOption(Path path) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(
                path.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static Path resolveAgentJar(Path gameDirectory) {
        try {
            URI location = RuntimeAgentBootstrap.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            Path candidate = Path.of(location).toAbsolutePath().normalize();
            if (isAgentJar(candidate)) return candidate;
        } catch (Exception ignored) {
        }
        Path mods = gameDirectory.resolve("mods");
        if (!Files.isDirectory(mods)) return null;
        try (var files = Files.list(mods)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(java.util.Locale.ROOT).contains("lightload"))
                    .filter(RuntimeAgentBootstrap::isAgentJar)
                    .findFirst().orElse(null);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean isAgentJar(Path path) {
        if (!Files.isRegularFile(path)) return false;
        try (JarFile jar = new JarFile(path.toFile(), false)) {
            return jar.getJarEntry("com/lightload/agent/LightloadAgent.class") != null
                    && jar.getManifest() != null
                    && "com.lightload.agent.LightloadAgent".equals(
                    jar.getManifest().getMainAttributes().getValue("Agent-Class"));
        } catch (Exception ignored) {
            return false;
        }
    }

    private static Path resolveGameDirectory() {
        Path workingDirectory = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        if (Files.isDirectory(workingDirectory.resolve("mods"))) return workingDirectory;
        String command = System.getProperty("sun.java.command", "");
        Matcher matcher = Pattern.compile("(?:^|\\s)--gameDir\\s+(.+?)(?=\\s+--[A-Za-z]|$)").matcher(command);
        if (matcher.find()) {
            String value = matcher.group(1).replace("\"", "").trim();
            return Path.of(value).toAbsolutePath().normalize();
        }
        return workingDirectory;
    }
}

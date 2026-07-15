package com.lightload.core;

import com.lightload.LightloadMod;
import net.minecraft.server.packs.resources.PreparableReloadListener;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;

/** Isolates configured third-party reload listeners without hiding JVM-fatal failures. */
public final class ResourceReloadFailureGuard {
    private ResourceReloadFailureGuard() {
    }

    public static List<PreparableReloadListener> wrap(List<PreparableReloadListener> listeners) {
        if (!StartupCoordinator.shouldIsolateModdedReloadFailures()) return listeners;
        boolean changed = false;
        PreparableReloadListener[] wrapped = new PreparableReloadListener[listeners.size()];
        for (int index = 0; index < listeners.size(); index++) {
            PreparableReloadListener listener = listeners.get(index);
            if (listener instanceof GuardedReloadListener || !shouldWrap(listener)) {
                wrapped[index] = listener;
            } else {
                wrapped[index] = new GuardedReloadListener(listener);
                changed = true;
            }
        }
        return changed ? List.of(wrapped) : listeners;
    }

    static boolean matches(String className, String configuredPatterns) {
        if (configuredPatterns == null || configuredPatterns.isBlank()) return false;
        return Arrays.stream(configuredPatterns.split(","))
                .map(String::trim).filter(pattern -> !pattern.isEmpty())
                .anyMatch(pattern -> "*".equals(pattern)
                        || pattern.endsWith(".*") && className.startsWith(pattern.substring(0, pattern.length() - 1))
                        || className.equals(pattern) || className.startsWith(pattern));
    }

    private static boolean shouldWrap(PreparableReloadListener listener) {
        String className = listener.getClass().getName();
        return !isCore(className) && matches(className,
                StartupCoordinator.config().isolatedResourceReloadListenerPatterns());
    }

    private static boolean isCore(String className) {
        return className.startsWith("net.minecraft.") || className.startsWith("net.minecraftforge.")
                || className.startsWith("com.mojang.") || className.startsWith("java.")
                || className.startsWith("javax.");
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) current = current.getCause();
        return current;
    }

    private static boolean fatal(Throwable throwable) {
        return throwable instanceof VirtualMachineError || throwable instanceof ThreadDeath;
    }

    private record GuardedReloadListener(PreparableReloadListener delegate)
            implements PreparableReloadListener {
        @Override
        public CompletableFuture<Void> reload(PreparationBarrier barrier, ResourceManager manager,
                                              ProfilerFiller preparationProfiler, ProfilerFiller reloadProfiler,
                                              Executor backgroundExecutor, Executor gameExecutor) {
            CompletableFuture<Void> future;
            try {
                future = delegate.reload(barrier, manager, preparationProfiler, reloadProfiler,
                        backgroundExecutor, gameExecutor);
            } catch (Throwable throwable) {
                return handle(throwable);
            }
            return future.handle((ignored, throwable) -> {
                if (throwable == null) return null;
                Throwable cause = unwrap(throwable);
                if (fatal(cause)) throw new CompletionException(cause);
                log(cause);
                return null;
            });
        }

        @Override
        public String getName() {
            return delegate.getName();
        }

        private CompletableFuture<Void> handle(Throwable throwable) {
            Throwable cause = unwrap(throwable);
            if (fatal(cause)) return CompletableFuture.failedFuture(cause);
            log(cause);
            return CompletableFuture.completedFuture(null);
        }

        private void log(Throwable throwable) {
            LightloadMod.LOGGER.error("[Lightload] Isolated resource reload failure in {} ({})",
                    delegate.getName(), delegate.getClass().getName(), throwable);
        }
    }
}

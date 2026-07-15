package com.lightload.core;

import com.lightload.LightloadMod;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.ForkJoinWorkerThread;
import java.util.concurrent.atomic.AtomicInteger;

public final class ReloadExecutorManager {
    private static volatile ExecutorService executor;

    private ReloadExecutorManager() {
    }

    public static ExecutorService select(ExecutorService vanilla) {
        var config = StartupCoordinator.config();
        if (!StartupCoordinator.aggressiveOptimizationsEnabled() || !config.dedicatedReloadExecutor()
                || CompatibilityManager.modernFixPresent()) return vanilla;
        ExecutorService current = executor;
        if (current != null) return current;
        synchronized (ReloadExecutorManager.class) {
            if (executor == null) executor = create(config.reloadThreads());
            return executor;
        }
    }

    static ExecutorService create(int configuredThreads) {
        int available = Runtime.getRuntime().availableProcessors();
        int parallelism = configuredThreads > 0 ? configuredThreads : Math.max(2, Math.min(available - 2, 16));
        parallelism = Math.max(1, parallelism);
        ClassLoader loader = ReloadExecutorManager.class.getClassLoader();
        AtomicInteger number = new AtomicInteger();
        LightloadMod.LOGGER.info("[Lightload] Using {} dedicated resource reload threads", parallelism);
        return new ForkJoinPool(parallelism, pool -> {
            ForkJoinWorkerThread thread = ForkJoinPool.defaultForkJoinWorkerThreadFactory.newThread(pool);
            thread.setName("Worker-Lightload-Reload-" + number.getAndIncrement());
            thread.setContextClassLoader(loader);
            return thread;
        }, (thread, error) -> LightloadMod.LOGGER.error("[Lightload] Resource reload worker failed: {}", thread.getName(), error), true);
    }
}

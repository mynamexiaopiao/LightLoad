package com.lightload.core;

import com.lightload.LightloadMod;
import com.mojang.datafixers.DataFixer;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

public final class LazyDataFixerManager {
    private static final AtomicBoolean PREWARMING = new AtomicBoolean();
    private static volatile LazyDataFixer instance;

    private LazyDataFixerManager() {
    }

    public static boolean enabled() {
        // DataFixers.DATA_FIXER is eagerly initialized before the 1.21.1 API exposes it.
        // The 1.20.1 createFixerUpper(Set) interception is therefore not safe to port.
        return false;
    }

    public static synchronized DataFixer create(Supplier<DataFixer> factory) {
        if (instance == null) {
            instance = new LazyDataFixer(factory);
            LightloadMod.LOGGER.info("[Lightload] Deferred DataFixer construction");
        }
        return instance;
    }

    public static void prewarmAsync(Runnable completion) {
        LazyDataFixer current = instance;
        if (current == null || current.initialized()) {
            completion.run();
            return;
        }
        if (!PREWARMING.compareAndSet(false, true)) return;
        Thread thread = new Thread(() -> {
            try {
                current.prewarm();
            } catch (RuntimeException | LinkageError error) {
                LightloadMod.LOGGER.warn("[Lightload] Background DataFixer warmup failed; it will retry on demand", error);
            } finally {
                PREWARMING.set(false);
                completion.run();
            }
        }, "lightload-dfu-warmup");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }
}

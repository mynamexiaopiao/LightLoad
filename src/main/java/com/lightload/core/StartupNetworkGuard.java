package com.lightload.core;

import com.lightload.LightloadMod;

import java.util.Locale;
import java.util.Set;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;

public final class StartupNetworkGuard {
    private static final AtomicBoolean CABLE_FACADES_LOGGED = new AtomicBoolean();
    private static final AtomicBoolean TITANIUM_LOGGED = new AtomicBoolean();
    private static final LongAdder DEFERRED_REQUESTS = new LongAdder();
    private static final Queue<Thread> DEFERRED_THREADS = new ConcurrentLinkedQueue<>();
    private static final Queue<Runnable> DEFERRED_ACTIONS = new ConcurrentLinkedQueue<>();
    private static final Set<String> DEFERRED_ACTION_KEYS = ConcurrentHashMap.newKeySet();

    private StartupNetworkGuard() {
    }

    public static boolean deferCableFacadesRequest(String listType) {
        if (!isCableFacadesList(listType)
                || !StartupCoordinator.shouldDeferRemoteConfigDownloads()) {
            return false;
        }
        DEFERRED_REQUESTS.increment();
        if (CABLE_FACADES_LOGGED.compareAndSet(false, true)) {
            LightloadMod.LOGGER.info(
                    "[Lightload] Using Cable Facades local configuration during startup; remote list requests are deferred");
        }
        return true;
    }

    public static long deferredRequests() {
        return DEFERRED_REQUESTS.sum();
    }

    public static boolean deferTitaniumRewardRequest(Thread thread) {
        if (thread == null || !StartupCoordinator.shouldDeferRemoteConfigDownloads()) return false;
        DEFERRED_THREADS.add(thread);
        DEFERRED_REQUESTS.increment();
        if (TITANIUM_LOGGED.compareAndSet(false, true)) {
            LightloadMod.LOGGER.info(
                    "[Lightload] Titanium reward list downloads will start after the first screen is ready");
        }
        return true;
    }

    public static void releaseDeferredRequests() {
        Thread thread;
        while ((thread = DEFERRED_THREADS.poll()) != null) {
            try {
                thread.start();
            } catch (IllegalThreadStateException exception) {
                LightloadMod.LOGGER.debug("[Lightload] Deferred Titanium request was already started", exception);
            }
        }
        Runnable action;
        while ((action = DEFERRED_ACTIONS.poll()) != null) {
            CompletableFuture.runAsync(action);
        }
    }

    public static boolean deferOptionalRemoteAction(String key, String className, String methodName) {
        // These calls initialize JDK HttpClient during parallel mod construction. Keep the
        // reliability guard available even when the performance optimizations are disabled.
        if (!StartupCoordinator.shouldGuardOptionalStartupNetwork()) return false;
        if (DEFERRED_ACTION_KEYS.add(key)) {
            DEFERRED_ACTIONS.add(() -> invokeOptionalRemoteAction(key, className, methodName));
            DEFERRED_REQUESTS.increment();
            LightloadMod.LOGGER.info("[Lightload] {} will load after the first screen is ready", key);
        }
        return true;
    }

    private static void invokeOptionalRemoteAction(String key, String className, String methodName) {
        try {
            Class<?> type = Class.forName(className, true, StartupNetworkGuard.class.getClassLoader());
            type.getMethod(methodName).invoke(null);
        } catch (Throwable throwable) {
            LightloadMod.LOGGER.warn("[Lightload] Deferred {} could not be loaded; continuing without optional online data",
                    key, throwable);
        }
    }

    static boolean isCableFacadesList(String listType) {
        if (listType == null) return false;
        return switch (listType.toLowerCase(Locale.ROOT)) {
            case "whitelist", "blacklist", "zfighting" -> true;
            default -> false;
        };
    }
}

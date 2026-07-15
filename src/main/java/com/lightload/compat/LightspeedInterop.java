package com.lightload.compat;

import com.lightload.LightloadMod;
import com.lightload.core.CompatibilityManager;
import com.lightload.core.StartupCoordinator;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicBoolean;

public final class LightspeedInterop {
    private static final AtomicBoolean LOGGED = new AtomicBoolean();
    private static final String GLOBAL_CACHE = "com.ccr4ft3r.lightspeed.cache.GlobalCache";

    private LightspeedInterop() {
    }

    public static void applyModernFixMode() {
        var config = StartupCoordinator.config();
        if (!CompatibilityManager.lightspeedModernFixInteropEnabled(config)) return;
        try {
            Class<?> cache = Class.forName(GLOBAL_CACHE, false, LightspeedInterop.class.getClassLoader());
            setBoolean(cache, "shouldAsyncPreloadPacks", false);
            setBoolean(cache, "shouldParallelizeResourcePackLookup", false);
            setBoolean(cache, "shouldCacheWalkedPaths", false);
            setBoolean(cache, "shouldCacheMaterials", false);
            setBoolean(cache, "shouldCacheResourceExistence", false);
            if (LOGGED.compareAndSet(false, true)) {
                LightloadMod.LOGGER.info("[Lightload] LightspeedRe ModernFix interop disabled overlapping resource and model caches");
            }
        } catch (ReflectiveOperationException | LinkageError exception) {
            if (LOGGED.compareAndSet(false, true)) {
                LightloadMod.LOGGER.warn("[Lightload] Could not apply LightspeedRe ModernFix interop; continuing without it", exception);
            }
        }
    }

    private static void setBoolean(Class<?> owner, String name, boolean value) throws ReflectiveOperationException {
        Field field = owner.getField(name);
        field.setBoolean(null, value);
    }
}

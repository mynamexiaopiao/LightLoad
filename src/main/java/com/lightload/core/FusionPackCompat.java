package com.lightload.core;

import net.minecraftforge.fml.ModList;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class FusionPackCompat {
    private static final String[] FIELD_SUFFIXES = {"overridesFolderName", "overridesFolderRoot", "overridesFolder"};
    private static final ConcurrentMap<Class<?>, List<Field>> FIELDS = new ConcurrentHashMap<>();

    private FusionPackCompat() {
    }

    public static boolean hasOverrides(Object pack) {
        if (pack == null || !isFusionLoaded()) return false;
        for (Field field : FIELDS.computeIfAbsent(pack.getClass(), FusionPackCompat::findFields)) {
            try {
                if (field.get(pack) != null) return true;
            } catch (IllegalAccessException | RuntimeException ignored) {
            }
        }
        return false;
    }

    private static boolean isFusionLoaded() {
        try {
            return ModList.get().isLoaded("fusion");
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private static List<Field> findFields(Class<?> type) {
        List<Field> result = new ArrayList<>();
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                for (String suffix : FIELD_SUFFIXES) {
                    if (!field.getName().endsWith(suffix)) continue;
                    try {
                        field.setAccessible(true);
                        result.add(field);
                    } catch (RuntimeException ignored) {
                    }
                    break;
                }
            }
        }
        return List.copyOf(result);
    }
}

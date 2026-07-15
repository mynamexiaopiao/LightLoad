package com.lightload.core;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;

public final class SrgbConversionCache {
    private static final int CACHE_BITS = 16;
    private static final int CACHE_MASK = (1 << CACHE_BITS) - 1;
    private static final int MIN_BITS = 956_301_312;
    private static final long[] ENTRIES = new long[1 << CACHE_BITS];
    private static final VarHandle ENTRY = MethodHandles.arrayElementVarHandle(long[].class);

    private SrgbConversionCache() {
    }

    public static int convert(float input, int[] table, float minimum, float maximum) {
        float value = input;
        if (value <= minimum) value = minimum;
        if (value > maximum) value = maximum;
        int bits = Float.floatToRawIntBits(value);
        int slot = (bits * 0x9E3779B9) >>> (32 - CACHE_BITS);
        long cached = (long) ENTRY.getOpaque(ENTRIES, slot);
        if ((int) (cached >>> 32) == bits) return (int) cached;

        int packed = table[(bits - MIN_BITS) >> 20];
        int result = ((packed >>> 16) << 9) + (packed & 0xFFFF) * ((bits >>> 12) & 0xFF) >>> 16;
        ENTRY.setOpaque(ENTRIES, slot, ((long) bits << 32) | (result & 0xFFFF_FFFFL));
        return result;
    }
}

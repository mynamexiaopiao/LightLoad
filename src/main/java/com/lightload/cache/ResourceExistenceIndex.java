package com.lightload.cache;

import java.io.DataInputStream;
import java.io.IOException;
import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

/** Immutable open-addressed string-to-boolean index without per-entry nodes or Boolean references. */
final class ResourceExistenceIndex extends AbstractMap<String, Boolean> {
    private static final int MAX_CAPACITY = 1 << 24;
    private final String[] keys;
    private final long[] trueSlots;
    private final int size;
    private transient Set<Entry<String, Boolean>> entries;

    private ResourceExistenceIndex(String[] keys, long[] trueSlots, int size) {
        this.keys = keys;
        this.trueSlots = trueSlots;
        this.size = size;
    }

    static ResourceExistenceIndex read(DataInputStream input, int entries) throws IOException {
        int capacity = tableCapacity(entries);
        String[] keys = new String[capacity];
        long[] trueSlots = new long[(capacity + 63) >>> 6];
        int unique = 0;
        for (int index = 0; index < entries; index++) {
            String key = input.readUTF();
            boolean value = input.readBoolean();
            int slot = findSlot(keys, key);
            if (keys[slot] == null) {
                keys[slot] = key;
                unique++;
            }
            set(trueSlots, slot, value);
        }
        return new ResourceExistenceIndex(keys, trueSlots, unique);
    }

    static ResourceExistenceIndex copyOf(Map<String, Boolean> source) {
        int capacity = tableCapacity(source.size());
        String[] keys = new String[capacity];
        long[] trueSlots = new long[(capacity + 63) >>> 6];
        int unique = 0;
        for (Entry<String, Boolean> entry : source.entrySet()) {
            String key = java.util.Objects.requireNonNull(entry.getKey());
            int slot = findSlot(keys, key);
            if (keys[slot] == null) {
                keys[slot] = key;
                unique++;
            }
            set(trueSlots, slot, Boolean.TRUE.equals(entry.getValue()));
        }
        return new ResourceExistenceIndex(keys, trueSlots, unique);
    }

    @Override
    public Boolean get(Object key) {
        if (!(key instanceof String string)) return null;
        int slot = locate(keys, string);
        return slot < 0 ? null : value(slot);
    }

    @Override
    public boolean containsKey(Object key) {
        return key instanceof String string && locate(keys, string) >= 0;
    }

    @Override
    public int size() {
        return size;
    }

    @Override
    public Set<Entry<String, Boolean>> entrySet() {
        Set<Entry<String, Boolean>> current = entries;
        if (current != null) return current;
        current = new AbstractSet<>() {
            @Override
            public Iterator<Entry<String, Boolean>> iterator() {
                return new Iterator<>() {
                    private int next = advance(0);

                    @Override
                    public boolean hasNext() {
                        return next < keys.length;
                    }

                    @Override
                    public Entry<String, Boolean> next() {
                        if (!hasNext()) throw new NoSuchElementException();
                        int slot = next;
                        next = advance(slot + 1);
                        return Map.entry(keys[slot], value(slot));
                    }

                    private int advance(int start) {
                        int slot = start;
                        while (slot < keys.length && keys[slot] == null) slot++;
                        return slot;
                    }
                };
            }

            @Override
            public int size() {
                return size;
            }
        };
        entries = current;
        return current;
    }

    private Boolean value(int slot) {
        return (trueSlots[slot >>> 6] & (1L << slot)) == 0 ? Boolean.FALSE : Boolean.TRUE;
    }

    private static void set(long[] values, int slot, boolean value) {
        long mask = 1L << slot;
        if (value) values[slot >>> 6] |= mask;
        else values[slot >>> 6] &= ~mask;
    }

    private static int locate(String[] table, String key) {
        int mask = table.length - 1;
        int slot = spread(key.hashCode()) & mask;
        while (true) {
            String candidate = table[slot];
            if (candidate == null) return -1;
            if (candidate.equals(key)) return slot;
            slot = (slot + 1) & mask;
        }
    }

    private static int findSlot(String[] table, String key) {
        int mask = table.length - 1;
        int slot = spread(key.hashCode()) & mask;
        while (table[slot] != null && !table[slot].equals(key)) slot = (slot + 1) & mask;
        return slot;
    }

    private static int spread(int hash) {
        return hash ^ (hash >>> 16);
    }

    private static int tableCapacity(int entries) {
        long required = Math.max(2L, (entries * 10L + 6L) / 7L);
        int capacity = 1;
        while (capacity < required && capacity < MAX_CAPACITY) capacity <<= 1;
        if (capacity < required) throw new IllegalArgumentException("Existence index is too large: " + entries);
        return capacity;
    }
}



package com.lightload.core;

import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

public final class VoxelShapeStartupCache {
    private static final int MAX_BOXES = 65_536;
    private static final int MAX_JOINS = 262_144;
    private static final ConcurrentHashMap<Object, VoxelShape> BOXES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Object, VoxelShape> JOINS = new ConcurrentHashMap<>();
    private static final ThreadLocal<MutableBoxKey> BOX_LOOKUP = ThreadLocal.withInitial(MutableBoxKey::new);
    private static final ThreadLocal<MutableJoinKey> JOIN_LOOKUP = ThreadLocal.withInitial(MutableJoinKey::new);
    private static final LongAdder HITS = new LongAdder();
    private static final LongAdder MISSES = new LongAdder();
    private static final LongAdder REJECTED = new LongAdder();
    private static volatile boolean startup = true;

    private VoxelShapeStartupCache() {
    }

    public static VoxelShape findBox(double minX, double minY, double minZ,
                                     double maxX, double maxY, double maxZ) {
        if (!enabled()) return null;
        VoxelShape shape = BOXES.get(BOX_LOOKUP.get().set(minX, minY, minZ, maxX, maxY, maxZ));
        if (shape != null) HITS.increment();
        return shape;
    }

    public static VoxelShape cacheBox(double minX, double minY, double minZ,
                                      double maxX, double maxY, double maxZ, VoxelShape shape) {
        if (!enabled()) return shape;
        if (BOXES.size() >= MAX_BOXES) {
            REJECTED.increment();
            return shape;
        }
        MutableBoxKey lookup = BOX_LOOKUP.get().set(minX, minY, minZ, maxX, maxY, maxZ);
        VoxelShape existing = BOXES.get(lookup);
        if (existing != null) return existing;
        existing = BOXES.putIfAbsent(lookup.immutable(), shape);
        if (existing == null) {
            MISSES.increment();
            return shape;
        }
        return existing;
    }

    public static VoxelShape findJoin(VoxelShape first, VoxelShape second, BooleanOp operation) {
        if (!enabled()) return null;
        VoxelShape shape = JOINS.get(JOIN_LOOKUP.get().set(first, second, operation));
        if (shape != null) HITS.increment();
        return shape;
    }

    public static VoxelShape cacheJoin(VoxelShape first, VoxelShape second, BooleanOp operation, VoxelShape shape) {
        if (!enabled()) return shape;
        if (JOINS.size() >= MAX_JOINS) {
            REJECTED.increment();
            return shape;
        }
        MutableJoinKey lookup = JOIN_LOOKUP.get().set(first, second, operation);
        VoxelShape existing = JOINS.get(lookup);
        if (existing != null) return existing;
        existing = JOINS.putIfAbsent(lookup.immutable(), shape);
        if (existing == null) {
            MISSES.increment();
            return shape;
        }
        return existing;
    }

    public static void finishStartup() {
        startup = false;
        BOXES.clear();
        JOINS.clear();
    }

    public static long hits() {
        return HITS.sum();
    }

    public static long misses() {
        return MISSES.sum();
    }

    public static int entries() {
        return BOXES.size() + JOINS.size();
    }

    public static long rejected() {
        return REJECTED.sum();
    }

    private static boolean enabled() {
        return startup && Boolean.parseBoolean(
                System.getProperty("lightload.voxelShapeCache.enabled", "true"));
    }

    private record BoxKey(long minX, long minY, long minZ, long maxX, long maxY, long maxZ) {
        private static BoxKey of(double minX, double minY, double minZ,
                                 double maxX, double maxY, double maxZ) {
            return new BoxKey(Double.doubleToLongBits(minX), Double.doubleToLongBits(minY),
                    Double.doubleToLongBits(minZ), Double.doubleToLongBits(maxX),
                    Double.doubleToLongBits(maxY), Double.doubleToLongBits(maxZ));
        }

        @Override
        public int hashCode() {
            return boxHash(minX, minY, minZ, maxX, maxY, maxZ);
        }
    }

    private static int boxHash(long minX, long minY, long minZ, long maxX, long maxY, long maxZ) {
        int hash = Long.hashCode(minX);
        hash = hash * 31 + Long.hashCode(minY);
        hash = hash * 31 + Long.hashCode(minZ);
        hash = hash * 31 + Long.hashCode(maxX);
        hash = hash * 31 + Long.hashCode(maxY);
        return hash * 31 + Long.hashCode(maxZ);
    }

    private static final class MutableBoxKey {
        private long minX;
        private long minY;
        private long minZ;
        private long maxX;
        private long maxY;
        private long maxZ;
        private int hash;

        private MutableBoxKey set(double minX, double minY, double minZ,
                                  double maxX, double maxY, double maxZ) {
            this.minX = Double.doubleToLongBits(minX);
            this.minY = Double.doubleToLongBits(minY);
            this.minZ = Double.doubleToLongBits(minZ);
            this.maxX = Double.doubleToLongBits(maxX);
            this.maxY = Double.doubleToLongBits(maxY);
            this.maxZ = Double.doubleToLongBits(maxZ);
            this.hash = boxHash(this.minX, this.minY, this.minZ, this.maxX, this.maxY, this.maxZ);
            return this;
        }

        private BoxKey immutable() {
            return new BoxKey(minX, minY, minZ, maxX, maxY, maxZ);
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object other) {
            if (other instanceof BoxKey key) {
                return minX == key.minX() && minY == key.minY() && minZ == key.minZ()
                        && maxX == key.maxX() && maxY == key.maxY() && maxZ == key.maxZ();
            }
            return other instanceof MutableBoxKey key && minX == key.minX && minY == key.minY
                    && minZ == key.minZ && maxX == key.maxX && maxY == key.maxY && maxZ == key.maxZ;
        }
    }

    private static final class JoinKey {
        private final VoxelShape first;
        private final VoxelShape second;
        private final BooleanOp operation;
        private final int hash;

        private JoinKey(VoxelShape first, VoxelShape second, BooleanOp operation) {
            this.first = first;
            this.second = second;
            this.operation = operation;
            this.hash = ((System.identityHashCode(first) * 31) + System.identityHashCode(second)) * 31
                    + System.identityHashCode(operation);
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof JoinKey key && first == key.first && second == key.second
                    && operation == key.operation;
        }
    }

    private static final class MutableJoinKey {
        private VoxelShape first;
        private VoxelShape second;
        private BooleanOp operation;
        private int hash;

        private MutableJoinKey set(VoxelShape first, VoxelShape second, BooleanOp operation) {
            this.first = first;
            this.second = second;
            this.operation = operation;
            this.hash = ((System.identityHashCode(first) * 31) + System.identityHashCode(second)) * 31
                    + System.identityHashCode(operation);
            return this;
        }

        private JoinKey immutable() {
            return new JoinKey(first, second, operation);
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(Object other) {
            if (other instanceof JoinKey key) {
                return first == key.first && second == key.second && operation == key.operation;
            }
            return other instanceof MutableJoinKey key && first == key.first && second == key.second
                    && operation == key.operation;
        }
    }
}

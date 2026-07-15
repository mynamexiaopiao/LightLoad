package com.lightload.core;

import net.minecraft.client.renderer.texture.Stitcher;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;

public final class FastTexturePacker {
    private FastTexturePacker() {
    }

    public static <T extends Stitcher.Entry> Result<T> pack(List<Stitcher.Holder<T>> holders,
                                                             int maximumWidth, int maximumHeight) {
        long area = 0;
        int widest = 1;
        int tallest = 1;
        for (Stitcher.Holder<T> holder : holders) {
            area += (long) holder.width() * holder.height();
            widest = Math.max(widest, holder.width());
            tallest = Math.max(tallest, holder.height());
        }
        int width = Mth.smallestEncompassingPowerOfTwo(widest);
        int height = Mth.smallestEncompassingPowerOfTwo(tallest);
        while ((long) width * height < area && (width < maximumWidth || height < maximumHeight)) {
            if (width <= height && width < maximumWidth) width <<= 1;
            else if (height < maximumHeight) height <<= 1;
            else break;
        }
        while (width <= maximumWidth && height <= maximumHeight) {
            List<Placement<T>> placements = tryPack(holders, width, height);
            if (placements != null) return new Result<>(width, height, placements);
            if (width <= height && width < maximumWidth) width <<= 1;
            else if (height < maximumHeight) height <<= 1;
            else if (width < maximumWidth) width <<= 1;
            else break;
        }
        return null;
    }

    private static <T extends Stitcher.Entry> List<Placement<T>> tryPack(List<Stitcher.Holder<T>> holders,
                                                                         int width, int height) {
        List<Node> skyline = new ArrayList<>();
        skyline.add(new Node(0, 0, width));
        List<Placement<T>> result = new ArrayList<>(holders.size());
        for (Stitcher.Holder<T> holder : holders) {
            Candidate best = null;
            for (int i = 0; i < skyline.size(); i++) {
                Candidate candidate = fit(skyline, i, holder.width(), holder.height(), width, height);
                if (candidate != null && (best == null || candidate.y < best.y
                        || candidate.y == best.y && candidate.x < best.x)) best = candidate;
            }
            if (best == null) return null;
            addSkyline(skyline, best.index, best.x, best.y + holder.height(), holder.width());
            result.add(new Placement<>(holder.entry(), best.x, best.y));
        }
        return result;
    }

    private static Candidate fit(List<Node> skyline, int index, int rectWidth, int rectHeight,
                                 int atlasWidth, int atlasHeight) {
        Node first = skyline.get(index);
        if (first.x + rectWidth > atlasWidth) return null;
        int remaining = rectWidth;
        int y = first.y;
        for (int i = index; remaining > 0; i++) {
            if (i >= skyline.size()) return null;
            Node node = skyline.get(i);
            y = Math.max(y, node.y);
            if (y + rectHeight > atlasHeight) return null;
            remaining -= node.width;
        }
        return new Candidate(index, first.x, y);
    }

    private static void addSkyline(List<Node> skyline, int index, int x, int y, int width) {
        skyline.add(index, new Node(x, y, width));
        for (int i = index + 1; i < skyline.size(); ) {
            Node previous = skyline.get(i - 1);
            Node current = skyline.get(i);
            int overlap = previous.x + previous.width - current.x;
            if (overlap <= 0) break;
            if (overlap < current.width) {
                skyline.set(i, new Node(current.x + overlap, current.y, current.width - overlap));
                break;
            }
            skyline.remove(i);
        }
        for (int i = 1; i < skyline.size(); ) {
            Node left = skyline.get(i - 1);
            Node right = skyline.get(i);
            if (left.y == right.y) {
                skyline.set(i - 1, new Node(left.x, left.y, left.width + right.width));
                skyline.remove(i);
            } else i++;
        }
    }

    private record Node(int x, int y, int width) {
    }

    private record Candidate(int index, int x, int y) {
    }

    public record Placement<T extends Stitcher.Entry>(T entry, int x, int y) {
    }

    public record Result<T extends Stitcher.Entry>(int width, int height, List<Placement<T>> placements) {
    }
}

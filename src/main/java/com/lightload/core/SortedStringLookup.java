package com.lightload.core;

import java.util.List;

public final class SortedStringLookup {
    private SortedStringLookup() {
    }

    public static int lowerBound(List<String> values, String target) {
        int low = 0;
        int high = values.size();
        while (low < high) {
            int middle = (low + high) >>> 1;
            if (values.get(middle).compareTo(target) < 0) low = middle + 1;
            else high = middle;
        }
        return low;
    }
}

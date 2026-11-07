package com.uav.flight.event;

import java.util.ArrayList;
import java.util.List;

/** Holes are absent heights from 1 through the highest received height. The highest height is not itself contiguous. */
public final class EventHeights {
    public record Range(long fromHeight, long toHeight) { }
    public record Page(List<Range> ranges, Long cursor) { }

    private EventHeights() { }

    public static List<Range> holes(List<Long> ascendingHeights) {
        var holes = new ArrayList<Range>();
        long previous = 0;
        for (long height : ascendingHeights) {
            if (height > previous + 1) holes.add(new Range(previous + 1, height - 1));
            previous = height;
        }
        return List.copyOf(holes);
    }

    /** Ranges are clipped to the closed comparison window and to heights that have already been received. */
    public static List<Range> holesInside(List<Long> ascendingHeights, long fromHeight, long toHeight) {
        if (ascendingHeights.isEmpty() || fromHeight > toHeight) return List.of();
        long highest = ascendingHeights.getLast();
        long end = Math.min(toHeight, highest);
        if (fromHeight > end) return List.of();
        var clipped = new ArrayList<Range>();
        for (Range hole : holes(ascendingHeights)) {
            long from = Math.max(hole.fromHeight(), fromHeight);
            long to = Math.min(hole.toHeight(), end);
            if (from <= to) clipped.add(new Range(from, to));
        }
        return List.copyOf(clipped);
    }

    public static Page page(List<Range> ranges, Long cursor, int limit) {
        int start = 0;
        if (cursor != null) {
            while (start < ranges.size() && ranges.get(start).toHeight() < cursor) start++;
            if (start < ranges.size() && ranges.get(start).fromHeight() < cursor) {
                var head = ranges.get(start);
                ranges = new ArrayList<>(ranges);
                ranges.set(start, new Range(cursor, head.toHeight()));
            }
        }
        int end = Math.min(ranges.size(), start + limit);
        Long next = end < ranges.size() ? ranges.get(end).fromHeight() : null;
        return new Page(List.copyOf(ranges.subList(start, end)), next);
    }
}

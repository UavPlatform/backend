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
        return holesInSpan(ascendingHeights.getLast(), ascendingHeights, fromHeight, toHeight);
    }

    /** Holes inside {@code [fromHeight, min(toHeight, highest)]}. {@code present} may be only that span. */
    public static List<Range> holesInSpan(long highest, List<Long> present, long fromHeight, long toHeight) {
        long end = Math.min(toHeight, highest);
        if (highest <= 0 || fromHeight > end) return List.of();
        var holes = new ArrayList<Range>();
        long previous = fromHeight - 1;
        for (long height : present) {
            if (height < fromHeight || height > end) continue;
            if (height > previous + 1) holes.add(new Range(previous + 1, height - 1));
            previous = height;
        }
        if (end > previous) holes.add(new Range(previous + 1, end));
        return List.copyOf(holes);
    }

    /** Holes strictly above the verified prefix and through the highest received height. */
    public static List<Range> holesAbove(long contiguous, List<Long> presentAbove, long highest) {
        if (highest <= contiguous) return List.of();
        return holesInSpan(highest, presentAbove, contiguous + 1, highest);
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

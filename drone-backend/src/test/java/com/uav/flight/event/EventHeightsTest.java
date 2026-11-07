package com.uav.flight.event;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class EventHeightsTest {
    @Test
    void pagesEveryHoleWithoutSkippingOrRepeating() {
        var heights = new ArrayList<Long>();
        for (long height = 1; height <= 31; height += 2) heights.add(height);
        var holes = EventHeights.holes(heights);
        var seen = new ArrayList<EventHeights.Range>();
        Long cursor = null;
        int pages = 0;
        do {
            var page = EventHeights.page(holes, cursor, 2);
            seen.addAll(page.ranges());
            cursor = page.cursor();
            pages++;
        } while (cursor != null && pages < 20);
        assertThat(seen).containsExactlyElementsOf(holes);
        assertThat(holes).hasSize(15);
        assertThat(EventHeights.holesInside(heights, 4, 8)).containsExactly(
                new EventHeights.Range(4, 4), new EventHeights.Range(6, 6), new EventHeights.Range(8, 8));
    }
}

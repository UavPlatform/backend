package com.uav.dispatch.service;

import com.uav.dispatch.pojo.entity.DispatchAssignment;
import com.uav.dispatch.pojo.entity.DispatchOrder;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AdminDispatchOrderServiceTest {
    @Test void likeEscapesWildcardsAndIgnoresBlank() {
        assertThat(AdminDispatchOrderService.like(null)).isNull();
        assertThat(AdminDispatchOrderService.like("  ")).isNull();
        assertThat(AdminDispatchOrderService.like(" a%b_c ")).isEqualTo("%a\\%b\\_c%");
    }

    @Test void timelineIsChronologicalAndSkipsMissingSteps() {
        LocalDateTime t = LocalDateTime.of(2031, 1, 1, 0, 0);
        DispatchOrder o = new DispatchOrder();
        o.setUserId(1L);
        o.setAssignedPilotId(2L);
        o.setCreatedAt(t);
        o.setStartedAt(t.plusMinutes(40));
        o.setDeliveredAt(t.plusMinutes(50));
        o.setReceiptAt(t.plusMinutes(55));
        o.setCompletedAt(t.plusMinutes(56));
        o.setSettledAt(t.plusMinutes(57));
        DispatchAssignment first = new DispatchAssignment();
        first.setPilotId(3L);
        first.setAcceptedAt(t.plusMinutes(5));
        first.setEndedAt(t.plusMinutes(10));
        first.setEndReason("PILOT_RELEASED");
        DispatchAssignment second = new DispatchAssignment();
        second.setPilotId(2L);
        second.setAcceptedAt(t.plusMinutes(20));
        second.setEndedAt(t.plusMinutes(60));
        var events = AdminDispatchOrderService.timeline(o, List.of(first, second));
        assertThat(events).extracting(e -> e.event()).containsExactly("CREATED", "ACCEPTED",
                "ASSIGNMENT_ENDED:PILOT_RELEASED", "ACCEPTED", "STARTED", "DELIVERED", "RECEIPT_CONFIRMED",
                "COMPLETED", "SETTLED", "ASSIGNMENT_ENDED");
    }
}

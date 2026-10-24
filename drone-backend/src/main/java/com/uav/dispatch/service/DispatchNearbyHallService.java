package com.uav.dispatch.service;
import com.uav.dispatch.mapper.*;
import com.uav.dispatch.pojo.vo.*;
import com.uav.server.config.DispatchNearbyConfig;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
public class DispatchNearbyHallService {
    private final DispatchOrderRepository orders;
    private final DispatchPilotPositionRepository positions;
    private final DispatchNearbyConfig config;
    public DispatchNearbyHallService(DispatchOrderRepository orders, DispatchPilotPositionRepository positions, DispatchNearbyConfig config) {
        this.orders = orders; this.positions = positions; this.config = config;
    }
    @Transactional(readOnly = true)
    public DispatchNearbyHallVO hall(Long pilot, int page) {
        var fix = positions.freshForPilot(pilot, System.currentTimeMillis());
        if (fix.isEmpty()) return new DispatchNearbyHallVO(List.of(), page, false, config.getRadiusKm(), "LOCATION_REQUIRED", null);
        var position = fix.get();
        var found = new ArrayList<DispatchNearbyOfferVO>();
        long eligibleSeen = 0, skip = page * 20L;
        // Filter before pagination. Stable creation/id ordering and bounded DB batches also support pre-V19 orders.
        for (int batch = 0; ; batch++) {
            var slice = orders.findByStageAndFundsStatusOrderByCreatedAtAsc("WAITING_ACCEPTANCE", "PAID", PageRequest.of(batch, 200, Sort.by("id")));
            for (var order : slice) {
                if (pilot.equals(order.getUserId())) continue;
                var distance = DispatchGeo.pickupDistance(order, position.getLatitude(), position.getLongitude());
                if (distance == null || distance > config.getRadiusKm() * 1000) continue;
                if (eligibleSeen++ < skip) continue;
                found.add(new DispatchNearbyOfferVO(DispatchMatchingService.offer(order), distance));
                if (found.size() == 21) return result(found.subList(0, 20), page, true, position.getExpiresAt());
            }
            if (!slice.hasNext()) return result(found, page, false, position.getExpiresAt());
        }
    }
    private DispatchNearbyHallVO result(List<DispatchNearbyOfferVO> items, int page, boolean next, long expires) {
        return new DispatchNearbyHallVO(items, page, next, config.getRadiusKm(), "READY", expires);
    }
}

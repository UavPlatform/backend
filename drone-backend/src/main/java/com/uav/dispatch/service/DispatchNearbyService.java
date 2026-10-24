package com.uav.dispatch.service;
import com.uav.dispatch.mapper.*;
import com.uav.dispatch.pojo.entity.DispatchOrder;
import com.uav.dispatch.pojo.vo.DispatchNearbyStatusVO;
import com.uav.server.config.DispatchNearbyConfig;
import com.uav.server.enums.ApiErrorCode;
import com.uav.server.exception.BusinessException;
import jakarta.persistence.EntityManager;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;

/** REQ-PILOT-MATCH-004 / REQ-SHIP-MATCH-004 / REQ-ALL-XC-005: inbox delivery, not phone push. */
@Service
public class DispatchNearbyService {
    private final DispatchOrderRepository orders;
    private final DispatchPilotPositionRepository positions;
    private final DispatchNotificationRepository notices;
    private final DispatchNotificationService notifications;
    private final DispatchNearbyConfig config;
    private final EntityManager entities;
    public DispatchNearbyService(DispatchOrderRepository orders, DispatchPilotPositionRepository positions,
            DispatchNotificationRepository notices, DispatchNotificationService notifications, DispatchNearbyConfig config, EntityManager entities) {
        this.orders = orders; this.positions = positions; this.notices = notices;
        this.notifications = notifications; this.config = config; this.entities = entities;
    }
    @Transactional
    public void process(String id) {
        var locked = orders.lockOrder(id);
        if (locked.isEmpty()) return;
        var order = locked.get(); entities.refresh(order);
        if (!"WAITING_ACCEPTANCE".equals(order.getStage()) || !"PAID".equals(order.getFundsStatus()) || order.getAssignedPilotId() != null) return;
        for (var position : positions.eligible(System.currentTimeMillis())) {
            if (position.getPilotId().equals(order.getUserId())) continue;
            var distance = DispatchGeo.pickupDistance(order, position.getLatitude(), position.getLongitude());
            if (distance != null && distance <= config.getRadiusKm() * 1000) notifications.nearbyOffer(order, position.getPilotId());
        }
        var since = order.getNearbyWaitingSince();
        if (since != null && !LocalDateTime.now().isBefore(since.plusSeconds(config.getReminderSeconds())))
            notifications.owner(order, "NEARBY_WAIT_TIMEOUT", "运单仍未接单", "本轮已等待较长时间，仍按配置的附近范围派单；不会自动扩大范围或取消订单。");
    }
    @Transactional(readOnly = true)
    public DispatchNearbyStatusVO status(Long owner, String id) {
        var order = orders.findById(id).filter(o -> owner.equals(o.getUserId()))
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.ORDER_NOT_FOUND));
        var since = order.getNearbyWaitingSince();
        String key = order.getId() + ":" + order.getAssignmentVersion() + ":";
        return new DispatchNearbyStatusVO(id, order.getAssignmentVersion(), order.getStage(), config.getRadiusKm(),
                notices.countByEventKeyAndAudience(key + "NEARBY_OFFER", "PILOT_OFFER"), since,
                since == null ? null : since.plusSeconds(config.getReminderSeconds()),
                notices.existsByRecipientIdAndEventKey(owner, key + "NEARBY_WAIT_TIMEOUT"), false);
    }
}

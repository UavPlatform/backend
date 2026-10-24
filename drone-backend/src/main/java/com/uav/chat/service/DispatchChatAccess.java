package com.uav.chat.service;

import com.uav.chat.pojo.entity.ChatSession;
import com.uav.chat.repository.ChatSessionRepository;
import com.uav.dispatch.mapper.DispatchOrderRepository;
import com.uav.dispatch.pojo.entity.DispatchOrder;
import com.uav.server.enums.ApiErrorCode;
import com.uav.server.exception.BusinessException;
import jakarta.persistence.EntityManager;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import java.util.Objects;
import java.util.Set;

/** REQ-SHIP-EXEC-002 / REQ-PILOT-EXEC-006: re-check assignment on all chat entry paths. */
@Service
public class DispatchChatAccess {
    private static final Set<String> ACTIVE = Set.of("PILOT_PREPARING", "PILOT_ARRIVED", "IN_TRANSIT", "DELIVERY_CONFIRMATION");
    private final ChatSessionRepository sessions;
    private final DispatchOrderRepository orders;
    private final EntityManager entities;
    public DispatchChatAccess(ChatSessionRepository sessions, DispatchOrderRepository orders, EntityManager entities) {
        this.sessions = sessions; this.orders = orders; this.entities = entities;
    }
    public boolean readable(Long sessionId, Long userId) {
        var session = sessions.findById(sessionId).orElse(null);
        if (session == null) return false;
        if (session.getDispatchOrderId() == null) return true;
        return orders.findById(session.getDispatchOrderId()).filter(o -> matches(session, o, userId))
                .filter(o -> ACTIVE.contains(o.getStage()) || "COMPLETED".equals(o.getStage())).isPresent();
    }
    // Caller holds a transaction: serialize sending with release/cancel/receipt on the same order lock.
    public void requireSending(ChatSession session, Long userId) {
        if (session == null || session.getDispatchOrderId() == null) return;
        var order = orders.lockOrder(session.getDispatchOrderId()).orElseThrow(this::denied);
        entities.refresh(order);
        if (!matches(session, order, userId) || !ACTIVE.contains(order.getStage())) throw denied();
    }
    private boolean matches(ChatSession session, DispatchOrder order, Long userId) {
        return order.getAssignedPilotId() != null
                && Objects.equals(session.getDispatchAssignmentVersion(), order.getAssignmentVersion())
                && (Objects.equals(userId, order.getUserId()) || Objects.equals(userId, order.getAssignedPilotId()));
    }
    private BusinessException denied() {
        return new BusinessException(HttpStatus.FORBIDDEN, ApiErrorCode.NO_PERMISSION, "本次接单已结束或无权发送订单消息");
    }
}

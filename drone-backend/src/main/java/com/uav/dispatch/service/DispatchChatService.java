package com.uav.dispatch.service;

import com.uav.chat.pojo.entity.ChatSession;
import com.uav.chat.pojo.entity.ChatUserSession;
import com.uav.chat.repository.ChatSessionRepository;
import com.uav.chat.service.UserSessionService;
import com.uav.dispatch.mapper.DispatchOrderRepository;
import com.uav.dispatch.pojo.vo.DispatchChatVO;
import com.uav.server.enums.ApiErrorCode;
import com.uav.server.exception.BusinessException;
import jakarta.persistence.EntityManager;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Set;

/** REQ-SHIP-EXEC-002 / REQ-PILOT-EXEC-006: server selects the two participants. */
@Service
public class DispatchChatService {
    private final DispatchOrderRepository orders;
    private final ChatSessionRepository sessions;
    private final UserSessionService members;
    private final EntityManager entities;
    public DispatchChatService(DispatchOrderRepository orders, ChatSessionRepository sessions,
                               UserSessionService members, EntityManager entities) {
        this.orders = orders; this.sessions = sessions; this.members = members; this.entities = entities;
    }
    @Transactional
    public DispatchChatVO open(Long caller, String id, long version) {
        var order = orders.lockOrder(id).orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.ORDER_NOT_FOUND));
        entities.refresh(order);
        if (!caller.equals(order.getUserId()) && !caller.equals(order.getAssignedPilotId()))
            throw new BusinessException(HttpStatus.FORBIDDEN, ApiErrorCode.NO_PERMISSION);
        if (order.getAssignedPilotId() == null || order.getAssignmentVersion() != version
                || !Set.of("PILOT_PREPARING", "PILOT_ARRIVED", "IN_TRANSIT", "DELIVERY_CONFIRMATION", "COMPLETED").contains(order.getStage()))
            throw new BusinessException(HttpStatus.CONFLICT, ApiErrorCode.DISPATCH_ASSIGNMENT_CHANGED);
        var existing = sessions.findByDispatchOrderIdAndDispatchAssignmentVersion(id, version);
        if (existing.isPresent()) return new DispatchChatVO(existing.get().getId(), id, version);
        // Completed orders may read an existing conversation, but do not create a new one.
        if ("COMPLETED".equals(order.getStage())) throw new BusinessException(HttpStatus.CONFLICT, ApiErrorCode.DISPATCH_ASSIGNMENT_CHANGED);
        long now = System.currentTimeMillis();
        var ids = List.of(order.getUserId(), order.getAssignedPilotId());
        var session = ChatSession.builder().name("派单订单沟通").type(0).ownerId(order.getUserId())
                .userIds(ids).dispatchOrderId(id).dispatchAssignmentVersion(version).createTime(now).build();
        sessions.saveAndFlush(session);
        members.saveAll(ids.stream().map(user -> ChatUserSession.builder().sessionId(session.getId()).userId(user)
                .joinTime(now).lastReadTime(now).build()).toList());
        return new DispatchChatVO(session.getId(), id, version);
    }
}

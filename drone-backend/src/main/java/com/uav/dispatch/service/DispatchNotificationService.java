package com.uav.dispatch.service;

import com.uav.dispatch.mapper.DispatchNotificationRepository;
import com.uav.dispatch.pojo.entity.DispatchNotification;
import com.uav.dispatch.pojo.entity.DispatchOrder;
import com.uav.dispatch.pojo.vo.*;
import com.uav.server.enums.ApiErrorCode;
import com.uav.server.exception.BusinessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import java.time.LocalDateTime;
import java.util.UUID;

/** REQ-ALL-XC-005 / ADR-0007: transactional inbox, independent of phone push delivery. */
@Service
public class DispatchNotificationService {
    private final DispatchNotificationRepository notices;
    private final DispatchEventStreamService streams;
    public DispatchNotificationService(DispatchNotificationRepository notices, DispatchEventStreamService streams) {
        this.notices = notices; this.streams = streams;
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public void changed(DispatchOrder order) {
        streams.afterCommit(order.getUserId(), order.getAssignedPilotId(), false);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void owner(DispatchOrder order, String kind, String title, String body) {
        append(order, order.getUserId(), "OWNER", kind, title, body);
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public void pilot(DispatchOrder order, String kind, String title, String body) {
        if (order.getAssignedPilotId() != null) append(order, order.getAssignedPilotId(), "PILOT", kind, title, body);
    }
    private void append(DispatchOrder order, Long recipient, String audience, String kind, String title, String body) {
        String key = order.getId() + ":" + order.getAssignmentVersion() + ":" + kind;
        if (notices.existsByRecipientIdAndEventKey(recipient, key)) return;
        var notice = new DispatchNotification();
        notice.setId(UUID.randomUUID().toString()); notice.setRecipientId(recipient); notice.setOrderId(order.getId());
        notice.setAudience(audience); notice.setEventKey(key); notice.setKind(kind); notice.setTitle(title); notice.setBody(body);
        notice.setCreatedAt(LocalDateTime.now()); notices.save(notice);
        boolean hall = java.util.Set.of("DEPOSIT_PAID", "CLAIMED", "REDISPATCHED", "CANCELLED").contains(kind);
        streams.afterCommit(order.getUserId(), order.getAssignedPilotId(), hall);
        if (!recipient.equals(order.getUserId()) && !recipient.equals(order.getAssignedPilotId())) {
            streams.afterCommit(recipient, null, false);
        }
    }
    @Transactional(propagation = Propagation.MANDATORY)
    public void nearbyOffer(DispatchOrder order, Long pilot) {
        append(order, pilot, "PILOT_OFFER", "NEARBY_OFFER", "附近有新的待接运单", "按最近有效位置匹配的附近运单。请打开附近订单查看当前状态；先接者得。");
    }
    @Transactional(readOnly = true)
    public long unread(Long caller) { return notices.countByRecipientIdAndReadAtIsNull(caller); }
    @Transactional(readOnly = true)
    public DispatchNotificationPageVO list(Long caller, int page) {
        if (page < 0 || page > 1000000) throw new BusinessException(ApiErrorCode.INVALID_PARAM);
        var result = notices.findByRecipientId(caller, PageRequest.of(page, 20, Sort.by(Sort.Direction.DESC, "createdAt", "id")));
        return new DispatchNotificationPageVO(result.stream().map(n -> new DispatchNotificationVO(n.getId(), n.getOrderId(),
                n.getAudience(), n.getKind(), n.getTitle(), n.getBody(), n.getCreatedAt(), n.getReadAt())).toList(),
                page, result.hasNext(), unread(caller));
    }
    @Transactional
    public void read(Long caller, String id) {
        if (notices.markRead(caller, id, LocalDateTime.now()) == 0 && !notices.existsByIdAndRecipientId(id, caller)) {
            throw new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.NO_PERMISSION, "通知不存在");
        }
        streams.afterCommit(caller, null, false);
    }
}

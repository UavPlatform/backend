package com.uav.equipment.service;

import com.uav.admin.mapper.AdminRepository;
import com.uav.admin.pojo.entity.Admin;
import com.uav.equipment.mapper.EquipmentNotificationRepository;
import com.uav.equipment.pojo.entity.EquipmentNotification;
import com.uav.equipment.pojo.vo.EquipmentNotificationPageVO;
import com.uav.equipment.pojo.vo.EquipmentNotificationVO;
import com.uav.server.enums.ApiErrorCode;
import com.uav.server.exception.BusinessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * REQ-ALL-XC-005 minimal: durable equipment/borrow notices.
 * Phone push and unified notification center UI are still TODO; this persists inbox rows only.
 * Inbox is scoped by (recipientId, audience) so admin.id and user.id collisions cannot leak.
 */
@Service
public class EquipmentNotificationService {
    public static final String AUDIENCE_ADMIN = "ADMIN";
    public static final String AUDIENCE_PILOT = "PILOT";
    public static final String KIND_PENDING = "BORROW_PENDING";
    public static final String KIND_APPROVED = "BORROW_APPROVED";
    public static final String KIND_REJECTED = "BORROW_REJECTED";
    public static final String KIND_OUT = "BORROW_OUT";
    public static final String KIND_RETURNED = "BORROW_RETURNED";

    private final EquipmentNotificationRepository notices;
    private final AdminRepository admins;

    public EquipmentNotificationService(EquipmentNotificationRepository notices, AdminRepository admins) {
        this.notices = notices; this.admins = admins;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void notifyAdmins(String borrowId, String kind, String title, String body) {
        for (Admin admin : admins.findAll()) {
            append(admin.getId(), AUDIENCE_ADMIN, borrowId, kind, title, body);
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void notifyPilot(Long pilotId, String borrowId, String kind, String title, String body) {
        append(pilotId, AUDIENCE_PILOT, borrowId, kind, title, body);
    }

    private void append(Long recipient, String audience, String borrowId, String kind, String title, String body) {
        String key = borrowId + ":" + kind;
        if (notices.existsByRecipientIdAndAudienceAndEventKey(recipient, audience, key)) return;
        var n = new EquipmentNotification();
        n.setId(UUID.randomUUID().toString());
        n.setRecipientId(recipient);
        n.setEventKey(key);
        n.setAudience(audience);
        n.setKind(kind);
        n.setTitle(title);
        n.setBody(body);
        n.setBorrowId(borrowId);
        n.setCreatedAt(LocalDateTime.now());
        notices.save(n);
    }

    /** role 1 → PILOT inbox; role 2 → ADMIN inbox. */
    public static String audienceForRole(Integer role) {
        if (Integer.valueOf(2).equals(role)) return AUDIENCE_ADMIN;
        if (Integer.valueOf(1).equals(role)) return AUDIENCE_PILOT;
        throw new BusinessException(HttpStatus.FORBIDDEN, ApiErrorCode.NO_PERMISSION, "无权查看借机通知");
    }

    @Transactional(readOnly = true)
    public EquipmentNotificationPageVO list(Long caller, Integer role, int page) {
        if (page < 0 || page > 1_000_000) throw new BusinessException(ApiErrorCode.INVALID_PARAM);
        String audience = audienceForRole(role);
        var result = notices.findByRecipientIdAndAudience(caller, audience,
                PageRequest.of(page, 20, Sort.by(Sort.Direction.DESC, "createdAt", "id")));
        return new EquipmentNotificationPageVO(
                result.stream().map(n -> new EquipmentNotificationVO(n.getId(), n.getAudience(), n.getKind(),
                        n.getTitle(), n.getBody(), n.getBorrowId(), n.getCreatedAt(), n.getReadAt())).toList(),
                page, result.hasNext(), notices.countByRecipientIdAndAudienceAndReadAtIsNull(caller, audience));
    }

    @Transactional
    public void read(Long caller, Integer role, String id) {
        String audience = audienceForRole(role);
        if (notices.markRead(caller, audience, id, LocalDateTime.now()) == 0
                && !notices.existsByIdAndRecipientIdAndAudience(id, caller, audience)) {
            throw new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.NO_PERMISSION, "通知不存在");
        }
    }
}

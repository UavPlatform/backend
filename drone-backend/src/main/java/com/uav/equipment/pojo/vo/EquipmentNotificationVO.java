package com.uav.equipment.pojo.vo;
import java.time.LocalDateTime;
public record EquipmentNotificationVO(
        String notificationId, String audience, String kind, String title, String body,
        String borrowId, LocalDateTime createdAt, LocalDateTime readAt) { }

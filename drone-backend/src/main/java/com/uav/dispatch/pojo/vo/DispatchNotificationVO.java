package com.uav.dispatch.pojo.vo;
import java.time.LocalDateTime;
public record DispatchNotificationVO(String notificationId, String orderId, String audience, String kind,
        String title, String body, LocalDateTime createdAt, LocalDateTime readAt) { }

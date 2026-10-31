package com.uav.equipment.pojo.vo;
import java.util.List;
public record EquipmentNotificationPageVO(List<EquipmentNotificationVO> items, int page, boolean hasNext, long unreadCount) { }

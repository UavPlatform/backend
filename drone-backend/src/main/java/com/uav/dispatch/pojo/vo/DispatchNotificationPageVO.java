package com.uav.dispatch.pojo.vo;
import java.util.List;
public record DispatchNotificationPageVO(List<DispatchNotificationVO> items, int page, boolean hasNext, long unreadCount) { }

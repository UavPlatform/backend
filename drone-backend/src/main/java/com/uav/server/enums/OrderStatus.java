package com.uav.server.enums;

import lombok.Getter;

@Getter
public enum OrderStatus {
    PENDING(0, "待支付"),
    PAID(1, "已支付"),
    CANCELLED(2, "已取消"),
    REFUNDED(3, "已退款"),
    COMPLETED(4, "已完成"),
    WAITING_CONFIRM(5, "待确认完成"),
    DISPUTED(6, "争议中"),
    /**
     * 待撮合（TASK-BACKEND-004 / ADR-0003 决定 3）：发单即建的草稿订单，不强制支付。
     * 不占用 {@code pending_key} 单例约束（仅 PENDING 占用），因此同一用户可并行发布多个需求；
     * 用户选定应征后转 PENDING（锁定 totalAmount = quotedAmount）再支付。
     */
    MATCHING(7, "待撮合");

    private final int code;
    private final String desc;

    OrderStatus(int code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static OrderStatus fromCode(Integer code) {
        if (code == null) return null;
        for (OrderStatus status : values()) {
            if (status.code == code) {
                return status;
            }
        }
        throw new IllegalArgumentException("无效的订单状态码: " + code);
    }

}

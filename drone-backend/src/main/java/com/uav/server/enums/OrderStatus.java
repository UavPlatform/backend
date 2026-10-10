package com.uav.server.enums;

import com.uav.server.exception.BusinessException;
import lombok.Getter;
import org.springframework.http.HttpStatus;

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

    /**
     * 解析查询入参：接受枚举名（如 {@code COMPLETED}）或状态码（如 {@code 4}），大小写不敏感。
     *
     * <p>供各列表接口的 {@code status} 查询参数使用，App 端与监管端共用同一份实现：非法取值一律以
     * {@link ApiErrorCode#INVALID_PARAM} 明确报错，禁止静默忽略。
     *
     * @param status 枚举名或状态码字符串，可为空
     * @return 对应状态；入参为空白时返回 null（语义为「不过滤」）
     */
    public static OrderStatus fromNameOrCode(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        String value = status.trim();
        try {
            return valueOf(value.toUpperCase());
        } catch (IllegalArgumentException ignore) {
            try {
                return fromCode(Integer.parseInt(value));
            } catch (Exception ignore2) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_PARAM,
                        "非法订单状态: " + status);
            }
        }
    }

}

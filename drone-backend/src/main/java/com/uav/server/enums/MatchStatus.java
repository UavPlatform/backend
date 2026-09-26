package com.uav.server.enums;

import com.uav.server.exception.BusinessException;
import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * 吊运撮合子状态（ADR-0003 决定 6「状态表达」；REQ-BACKEND-001 撮合状态机）。
 *
 * <p>与 {@link TaskStatus} / {@link OrderStatus} 的映射（实现说明，app 显示以本枚举为准）：
 *
 * <pre>
 * TaskStatus      OrderStatus      MatchStatus            含义
 * ────────────────────────────────────────────────────────────────────────────
 * IDLE            MATCHING         SEEKING_RIDER          发单完成，待撮合（草稿订单，不强制支付）
 * IDLE            MATCHING         NEGOTIATING            已有飞手应征，洽谈中
 * IDLE            PENDING          AWAITING_PAYMENT       用户已选定飞手+约定时间，锁定报价，待支付
 * IDLE            PAID             AWAITING_RIDER_CONFIRM 支付成功，待飞手确认约定时间
 * IN_PROGRESS     PAID             CONFIRMED              双方确认 → 可执飞（djifly 门禁点）
 * COMPLETED       WAITING_CONFIRM  PENDING_ACCEPTANCE     飞手交付（已上传履约证据），待用户验收
 * COMPLETED       COMPLETED        CLOSED                 用户确认/超时自动确认 → 结案
 * </pre>
 *
 * <p>取消/退款旁路：订单 CANCELLED 后回退 {@code NEGOTIATING}（重新开放撮合）；REFUNDED 终态
 * 保持当前撮合状态由提示矩阵按订单终态优先展示。
 *
 * <p><b>合法迁移表</b>（{@link #canTransitionTo}）：非法迁移一律以
 * {@link ApiErrorCode#MATCH_STATUS_INVALID} 明确报错，禁止静默落库。
 */
@Getter
public enum MatchStatus {

    /** 招募飞手中：任务已发布，尚无飞手应征。 */
    SEEKING_RIDER("招募飞手中"),

    /** 洽谈中：至少一条应征，等待用户选定。 */
    NEGOTIATING("洽谈中"),

    /** 待支付：已选定应征并锁定 totalAmount = quotedAmount 与约定时间。 */
    AWAITING_PAYMENT("待支付"),

    /** 待飞手确认：已支付，等待飞手确认接单与约定时间。 */
    AWAITING_RIDER_CONFIRM("待飞手确认"),

    /** 双方已确认：允许 TaskStatus → IN_PROGRESS（双确认门禁放行点）。 */
    CONFIRMED("双方已确认"),

    /** 待验收：飞手已交付并上传履约证据，等待用户确认。 */
    PENDING_ACCEPTANCE("待验收"),

    /** 已结案：用户确认或超时自动确认，撮合终态。 */
    CLOSED("已结案");

    private final String description;

    MatchStatus(String description) {
        this.description = description;
    }

    /**
     * 合法迁移表（ADR-0003 决定 6）。同状态视为幂等（如选定阶段重新锁定金额）合法。
     *
     * <pre>
     * SEEKING_RIDER       → NEGOTIATING / AWAITING_PAYMENT
     * NEGOTIATING         → AWAITING_PAYMENT / AWAITING_RIDER_CONFIRM（订单已支付的重新选定）
     * AWAITING_PAYMENT    → AWAITING_RIDER_CONFIRM（支付成功）/ NEGOTIATING（取消支付）
     * AWAITING_RIDER_CONFIRM → CONFIRMED（飞手确认）/ NEGOTIATING（重新开放撮合）
     * CONFIRMED           → PENDING_ACCEPTANCE（交付验收）/ NEGOTIATING（飞手取消接单）
     * PENDING_ACCEPTANCE  → CLOSED（用户确认 / 超时自动确认）
     * CLOSED              → （终态，无出边）
     * </pre>
     */
    public boolean canTransitionTo(MatchStatus target) {
        if (target == null) {
            return false;
        }
        if (target == this) {
            return true; // 幂等：如重复选定时重新锁定金额
        }
        return switch (this) {
            case SEEKING_RIDER -> target == NEGOTIATING || target == AWAITING_PAYMENT;
            case NEGOTIATING -> target == AWAITING_PAYMENT || target == AWAITING_RIDER_CONFIRM;
            case AWAITING_PAYMENT -> target == AWAITING_RIDER_CONFIRM || target == NEGOTIATING;
            case AWAITING_RIDER_CONFIRM -> target == CONFIRMED || target == NEGOTIATING;
            case CONFIRMED -> target == PENDING_ACCEPTANCE || target == NEGOTIATING;
            case PENDING_ACCEPTANCE -> target == CLOSED;
            case CLOSED -> false;
        };
    }

    /**
     * 状态机守卫：非法迁移抛 {@link ApiErrorCode#MATCH_STATUS_INVALID}（明确错误码，不静默失败）。
     *
     * @param from 当前撮合状态（可为 null，视作 SEEKING_RIDER 之前的存量态，同样拒绝未知迁移）
     * @param to   目标撮合状态
     */
    public static void requireTransition(MatchStatus from, MatchStatus to) {
        if (from == null || !from.canTransitionTo(to)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.MATCH_STATUS_INVALID,
                    "撮合状态不允许从 " + (from == null ? "未知" : from.name())
                            + " 迁移到 " + (to == null ? "未知" : to.name()));
        }
    }
}

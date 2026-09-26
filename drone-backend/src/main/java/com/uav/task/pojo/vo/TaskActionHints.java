package com.uav.task.pojo.vo;

import com.uav.server.enums.MatchStatus;
import com.uav.server.enums.OrderStatus;
import com.uav.server.enums.TaskStatus;

/**
 * 1B-9a 状态矩阵 + TASK-BACKEND-004 撮合子状态：任务状态 × 订单状态 × 撮合状态 → 操作提示
 * （全景 P1-8）。服务端统一计算，客户端（app/运营端）直接渲染 actionHint 字段，避免各自拼装漂移。
 *
 * <p>优先级（ADR-0003 状态表达）：订单终态（退款/取消/完成）→ 撮合子状态（matchStatus，
 * 覆盖全部新阶段）→ 无撮合状态的存量数据回退旧双状态矩阵。
 */
public final class TaskActionHints {

    private TaskActionHints() {
    }

    /**
     * 计算操作提示；无法识别的组合返回中性兜底文案（不返回 null，便于直接渲染）。
     *
     * @param matchStatus 撮合子状态（可为 null：存量数据未回填时回退双状态矩阵）
     */
    public static String hint(TaskStatus taskStatus, OrderStatus orderStatus, MatchStatus matchStatus) {
        // 1) 订单终态优先（退款/取消/完成与撮合状态解耦）
        if (orderStatus == OrderStatus.REFUNDED) {
            return "已退款";
        }
        if (orderStatus == OrderStatus.CANCELLED) {
            return "已取消";
        }
        if (orderStatus == OrderStatus.COMPLETED) {
            return "已完成：感谢使用";
        }

        // 2) 撮合子状态（TASK-BACKEND-004 主路径）
        if (matchStatus != null) {
            return switch (matchStatus) {
                case SEEKING_RIDER -> "发布中：等待飞手应征";
                case NEGOTIATING -> "洽谈中：已收到飞手应征报价，选定飞手并下单";
                case AWAITING_PAYMENT -> "待支付：已锁定系统报价与约定时间，请完成支付";
                case AWAITING_RIDER_CONFIRM -> "已支付：等待飞手确认约定时间";
                case CONFIRMED -> taskStatus == TaskStatus.IN_PROGRESS
                        ? "执行中：飞手正在执行任务"
                        : "已确认：双方已确认，任务待执行";
                case PENDING_ACCEPTANCE -> "待验收：飞手已完成，请确认验收";
                case CLOSED -> "已结案";
            };
        }

        // 3) 存量回退：旧 任务状态×订单状态 矩阵（matchStatus 未回填的数据）
        if (orderStatus == OrderStatus.PENDING) {
            return "待支付：支付后任务将进入接单大厅";
        }
        if (orderStatus == OrderStatus.PAID) {
            if (taskStatus == TaskStatus.IDLE) {
                return "已支付：等待飞手接单";
            }
            if (taskStatus == TaskStatus.IN_PROGRESS) {
                return "执行中：飞手正在执行任务";
            }
        }
        if (orderStatus == OrderStatus.WAITING_CONFIRM) {
            return "待验收：飞手已完成，请确认验收";
        }
        if (orderStatus == OrderStatus.MATCHING) {
            return "发布中：等待飞手应征";
        }
        return "状态更新中";
    }
}

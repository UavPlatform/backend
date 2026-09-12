package com.uav.task.pojo.vo;

import com.uav.server.enums.OrderStatus;
import com.uav.server.enums.TaskStatus;

/**
 * 1B-9a 状态矩阵：任务状态 × 订单状态 → 操作提示（全景 P1-8）。
 * 服务端统一计算，客户端（app/运营端）直接渲染 actionHint 字段，避免各自拼装漂移。
 */
public final class TaskActionHints {

    private TaskActionHints() {
    }

    /**
     * 计算操作提示；无法识别的组合返回中性兜底文案（不返回 null，便于直接渲染）。
     */
    public static String hint(TaskStatus taskStatus, OrderStatus orderStatus) {
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
        if (orderStatus == OrderStatus.COMPLETED) {
            return "已完成：感谢使用";
        }
        if (orderStatus == OrderStatus.CANCELLED) {
            return "已取消";
        }
        if (orderStatus == OrderStatus.REFUNDED) {
            return "已退款";
        }
        return "状态更新中";
    }
}

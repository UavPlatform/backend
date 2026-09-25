package com.uav.server.notify;

import com.uav.chat.pojo.enums.MsgType;

import java.util.Map;

/**
 * 待派发的系统通知草稿（1B-3，裁决 Q9=A）。
 * 由 Hibernate 拦截器在事务刷洗时捕获实体状态迁移，事务提交后交由
 * SystemNotificationService 落库并经聊天 WS 推送。
 *
 * @param recipientId 接收方用户 id
 * @param msgType     消息大类（NOTICE=系统通知 / ORDER=订单状态，复用 BE P2-30 预留枚举）
 * @param name        事件名（ORDER_PAID / TASK_ACCEPTED / TASK_CANCELLED / TASK_COMPLETED /
 *                    ORDER_WAITING_CONFIRM / ORDER_CONFIRMED）
 * @param data        结构化数据（orderNum/taskNum/taskName/riderId 等）
 * @param text        人类可读文案
 * @param taskId      关联任务 id（确认完成通知用于解析接单飞手，可为 null）
 */
public record NotificationDraft(
        Long recipientId,
        MsgType msgType,
        String name,
        Map<String, Object> data,
        String text,
        Long taskId) {

    public static NotificationDraft of(Long recipientId, MsgType msgType, String name,
                                       Map<String, Object> data, String text) {
        return new NotificationDraft(recipientId, msgType, name, data, text, null);
    }

    public static NotificationDraft forTask(Long recipientId, MsgType msgType, String name,
                                            Map<String, Object> data, String text, Long taskId) {
        return new NotificationDraft(recipientId, msgType, name, data, text, taskId);
    }
}

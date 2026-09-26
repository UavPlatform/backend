package com.uav.chat.pojo.vo;

import lombok.Data;

/**
 * 任务会话聚合视图（TASK-BACKEND-005 / REQ-BACKEND-001 / ADR-0003）。
 *
 * <p>用于 {@code GET /task/{taskNum}/chat-sessions}：任务属主按 taskNum 聚合与各意向飞手的
 * 一对一会话；应征/选定飞手调用时仅返回自己与任务属主的会话。
 */
@Data
public class TaskChatSessionVO {

    private Long sessionId;

    private String name;

    private Integer type;

    private String taskNum;

    /** 关联的应征记录 ID（ADR-0003「及可选 applicationId」）。 */
    private Long applicationId;

    private Long ownerId;

    /** 该会话对应的应征飞手（会话成员中非任务属主的一方）。 */
    private Long riderId;

    private String riderName;

    /** 相对当前访问者的对方用户 ID（属主看飞手，飞手看属主）。 */
    private Long otherUserId;

    private String otherUserName;

    private Long createTime;

    private String lastMessage;

    private Long lastMessageTime;

    /** 当前访问者在该会话的未读数（无读取记录时为 0）。 */
    private Integer unreadCount;
}

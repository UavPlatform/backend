package com.uav.chat.pojo.vo;

import lombok.Data;

import java.util.List;

@Data
public class SessionVO {
    private Long id;

    private String name;                 // 会话名称（群名称，或自动生成如“张三-李四”）

    private Integer type;                // 0: 一对一，1: 多人聊天室/群组

    private List<Long> userIds;          // 会话成员用户ID列表

    private Long ownerId;                // 群主/创建者ID（仅群组有意义）

    private String avatar;               // 会话头像（可选）
    private String description;          // 会话描述（可选）

    private Long createTime;    // 群聊创建时间
    private String otherUserName; // 私聊对方的用户名
    private String lastMessage;   // 最后一条消息内容
    private Long lastMessageTime; // 最后一条消息时间
}

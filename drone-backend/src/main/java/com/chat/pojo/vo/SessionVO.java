package com.chat.pojo.vo;

import lombok.Data;

import java.time.LocalDateTime;
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

    private LocalDateTime createTime;    // 群聊创建时间

    public void setSessionId(Long id) {

    }
}

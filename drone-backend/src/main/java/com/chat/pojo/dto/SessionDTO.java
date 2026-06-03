package com.chat.pojo.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

@Data
public class SessionDTO {
    @NotNull
    private String name;                 // 会话名称（群名称，或自动生成如“张三-李四”）

    @NotNull
    private Integer type;                // 0: 一对一，1; 多人聊天室/群组

    @NotNull
    private List<Long> userIds;          // 会话成员用户ID列表

    private String avatar;               // 会话头像（可选）
    private String description;          // 会话描述（可选）
}

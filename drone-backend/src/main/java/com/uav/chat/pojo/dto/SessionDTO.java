package com.uav.chat.pojo.dto;

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

    /**
     * 绑定的吊运任务编号（可选，ADR-0003）：非空时创建「任务会话」——参与方固定为
     * 任务属主 ↔ 该任务的应征飞手（type 必须为 0），同一任务+同一飞手去重复用；
     * 为空时走既有通用会话逻辑，本字段与 {@code applicationId} 被忽略。
     */
    private String taskNum;

    /**
     * 关联的应征记录 ID（可选，ADR-0003「及可选 applicationId」）：提供时必须与
     * 该任务该飞手的实际应征记录一致，否则 400；不提供时由服务端按应征记录自动带上。
     * 仅任务会话生效。
     */
    private Long applicationId;
}

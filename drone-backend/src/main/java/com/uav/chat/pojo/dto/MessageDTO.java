package com.uav.chat.pojo.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class MessageDTO {
    private String msgId;
    private Long fromUserId;
    @NotNull
    private Long sessionId;
    @NotNull
    private String content;

    private Long createTime;
}

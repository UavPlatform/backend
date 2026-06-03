package com.chat.pojo.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class MessageDTO {
    @NotNull
    private Long fromUserId;
    @NotNull
    private Long sessionId;
    @NotNull
    private String content;
}

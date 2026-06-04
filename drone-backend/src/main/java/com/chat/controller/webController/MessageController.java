package com.chat.controller.webController;

import com.chat.pojo.dto.MessageDTO;
import com.chat.service.MessageService;
import com.drone.pojo.result.Result;
import com.drone.server.annotation.OperationLog;
import com.drone.server.annotation.RateLimiter;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@Tag(name = "chat message API", description = "聊天消息接口")
@RestController
@RequestMapping("/chat/Message")
@Slf4j
public class MessageController {

    @Autowired
    private MessageService messageService;

    @OperationLog("发送消息")
    @RateLimiter(limit = 5, windowSeconds = 60)
    @Operation(summary = "发送消息")
    @PostMapping("/send")
    public Object sendMessage(@Valid @RequestBody MessageDTO dto) {
        messageService.sendMessage(dto);
        return Result.success();
    }
    @OperationLog("撤回消息")
    @RateLimiter(limit = 5, windowSeconds = 60)
    @Operation(summary = "撤回消息")
    @PostMapping("/recall/{messageId}")
    public Object recallMessage(@Valid @PathVariable String messageId) {
        messageService.recallMessage(messageId);
        return Result.success();
    }
    @OperationLog("删除消息")
    @RateLimiter(limit = 5, windowSeconds = 60)
    @Operation(summary = "删除消息")
    @PostMapping("/delete/{messageId}")
    public Object deleteMessage(@Valid @PathVariable String messageId) {
        messageService.deleteMessage(messageId);
        return Result.success();
    }
    @OperationLog("获取消息")
    @RateLimiter(limit = 5, windowSeconds = 60)
    @Operation(summary = "获取消息")
    @GetMapping("/messages/{sessionId}")
    public Object getMessages(@Valid @PathVariable Long sessionId) {
        return Result.success(messageService.getMessages(sessionId));
    }
}
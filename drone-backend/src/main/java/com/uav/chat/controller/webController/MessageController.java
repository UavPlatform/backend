package com.uav.chat.controller.webController;

import com.uav.chat.pojo.dto.MessageDTO;
import com.uav.chat.service.MessageService;
import com.uav.server.annotation.OperationLog;
import com.uav.server.annotation.RateLimiter;
import com.uav.server.result.Result;
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
    @Operation(summary = "发送消息", description = "发送消息")
    @PostMapping("/send")
    public Object sendMessage(@Valid @RequestBody MessageDTO dto) {
        messageService.sendMessage(dto);
        return Result.success();
    }
    @OperationLog("撤回消息")
    @RateLimiter(limit = 5, windowSeconds = 60)
    @Operation(summary = "撤回消息", description = "撤回消息")
    @PostMapping("/recall/{msgId}")
    public Object recallMessage(@Valid @PathVariable String msgId) {
        messageService.recallMessage(msgId);
        return Result.success();
    }
    @OperationLog("删除消息")
    @RateLimiter(limit = 5, windowSeconds = 60)
    @Operation(summary = "删除消息", description = "删除消息")
    @PostMapping("/delete/{msgId}")
    public Object deleteMessage(@Valid @PathVariable String msgId) {
        messageService.deleteMessage(msgId);
        return Result.success();
    }
    @OperationLog("获取消息")
    @RateLimiter(limit = 5, windowSeconds = 60)
    @Operation(summary = "获取消息", description = "获取消息")
    @GetMapping("/messages/{msgId}")
    public Object getMessages(@Valid @PathVariable Long msgId) {
        return Result.success(messageService.getMessages(msgId));
    }

    @OperationLog("同步离线消息")
    @RateLimiter(limit = 2, windowSeconds = 30)
    @Operation(summary = "同步离线消息", description = "拉取所有会话中上次读取后错过的消息，并更新 lastReadTime")
    @GetMapping("/sync")
    public Object syncMessages() {
        Long userId = com.uav.server.util.UserContext.getUserId();
        return Result.success(messageService.getUnreadMessages(userId));
    }
}
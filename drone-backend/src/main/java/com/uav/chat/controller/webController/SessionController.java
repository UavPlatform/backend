package com.uav.chat.controller.webController;

import com.uav.chat.pojo.dto.SessionDTO;
import com.uav.chat.service.SessionService;
import com.uav.server.annotation.OperationLog;
import com.uav.server.annotation.RateLimiter;
import com.uav.server.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@Tag(name = "chat session API", description = "会话相关接口")
@RestController
@RequestMapping("/chat/session")
@Slf4j
public class SessionController {

    @Autowired
    private SessionService sessionService;


    @OperationLog("创建会话")
    @RateLimiter(limit = 5, windowSeconds = 60)
    @Operation(summary = "创建会话")
    @PostMapping("/create")
    public Object createSession(@Valid @RequestBody SessionDTO dto) {
        sessionService.createSession(dto);
        return Result.success();
    }
    @OperationLog("删除会话")
    @RateLimiter(limit = 5, windowSeconds = 60)
    @Operation(summary = "删除会话")
    @PostMapping("/delete/{sessionId}")
    public Object deleteSession(@Valid @PathVariable Long sessionId) {
        sessionService.deleteSession(sessionId);
        return Result.success();
    }
    @OperationLog("列出会话")
    @RateLimiter(limit = 5, windowSeconds = 60)
    @Operation(summary = "列出会话")
    @GetMapping("/list")
    public Object listSession() {
        return Result.success(sessionService.listSession());
    }
}
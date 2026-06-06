package com.uav.chat.controller;

import com.uav.chat.pojo.dto.SessionDTO;
import com.uav.chat.pojo.vo.SessionVO;
import com.uav.chat.service.SessionService;
import com.uav.server.result.Result;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "chat session API")
@RestController
@RequestMapping("/chat/session")
@Slf4j
public class SessionController {

    @Autowired
    private SessionService sessionService;

    @PostMapping("/create")
    public Result<Void> createSession(@Valid @RequestBody SessionDTO dto) {
        sessionService.createSession(dto);
        return Result.success();
    }

    @PostMapping("/delete/{sessionId}")
    public Result<Void> deleteSession(@PathVariable Long sessionId) {
        sessionService.deleteSession(sessionId);
        return Result.success();
    }

    @GetMapping("/list")
    public Result<List<SessionVO>> listSession() {
        List<SessionVO> sessions = sessionService.listSession();
        return Result.success(sessions);
    }
}

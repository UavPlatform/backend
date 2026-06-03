package com.chat.controller.webController;

import com.chat.pojo.dto.SessionDTO;
import com.chat.pojo.vo.SessionVO;
import com.chat.service.SessionService;
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
public class sessionController {

    @Autowired
    private SessionService sessionService;

    @PostMapping("/create")
    public void createSession(@Valid @RequestBody SessionDTO dto) {
        sessionService.createSession(dto);
    }
    @PostMapping("/delete/{sessionId}")
    public void deleteSession(@Valid @PathVariable Long sessionId) {
        sessionService.deleteSession(sessionId);
    }
    @GetMapping("/list")
    public List<SessionVO> listSession() {
        return sessionService.listSession();
    }
}
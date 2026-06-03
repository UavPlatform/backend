package com.chat.controller.webController;

import com.chat.pojo.dto.MessageDTO;
import com.chat.service.MessageService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@Tag(name = "chat message API")
@RestController
@RequestMapping("/chat/Message")
@Slf4j
public class messageController {

    @Autowired
    private MessageService messageService;

    @PostMapping("/send")
    public Object sendMessage(@Valid @RequestBody MessageDTO dto) {
        messageService.sendMessage(dto);
        return "success";
    }
    @PostMapping("/recall/{messageId}")
    public Object recallMessage(@Valid @PathVariable Long messageId) {
        messageService.recallMessage(messageId);
        return "success";
    }
    @PostMapping("/delete/{messageId}")
    public Object deleteMessage(@Valid @PathVariable Long messageId) {
        messageService.deleteMessage(messageId);
        return "success";
    }
    @GetMapping("/messages/{sessionId}")
    public Object getMessages(@Valid @PathVariable Long sessionId) {
        return messageService.getMessages(sessionId);
    }
}
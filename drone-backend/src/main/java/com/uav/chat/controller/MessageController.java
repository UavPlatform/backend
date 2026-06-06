package com.uav.chat.controller;

import com.uav.chat.pojo.dto.MessageDTO;
import com.uav.chat.pojo.vo.MessageVO;
import com.uav.chat.service.MessageService;
import com.uav.server.result.Result;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "chat message API")
@RestController
@RequestMapping("/chat/Message")
@Slf4j
public class MessageController {

    @Autowired
    private MessageService messageService;

    @PostMapping("/send")
    public Result<Void> sendMessage(@Valid @RequestBody MessageDTO dto) {
        messageService.sendMessage(dto);
        return Result.success();
    }

    @PostMapping("/recall/{messageId}")
    public Result<Void> recallMessage(@PathVariable Long messageId) {
        messageService.recallMessage(messageId);
        return Result.success();
    }

    @PostMapping("/delete/{messageId}")
    public Result<Void> deleteMessage(@PathVariable Long messageId) {
        messageService.deleteMessage(messageId);
        return Result.success();
    }

    @GetMapping("/messages/{sessionId}")
    public Result<List<MessageVO>> getMessages(@PathVariable Long sessionId) {
        List<MessageVO> messages = messageService.getMessages(sessionId);
        return Result.success(messages);
    }
}
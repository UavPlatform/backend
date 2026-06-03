package com.chat.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.chat.pojo.dto.SessionDTO;
import com.chat.pojo.entity.ChatSession;
import com.chat.pojo.vo.SessionVO;
import jakarta.validation.Valid;

import java.util.List;

public interface SessionService extends IService<ChatSession> {
    void createSession(@Valid SessionDTO dto);

    void deleteSession(@Valid Long sessionId);

    List<SessionVO> listSession();
}

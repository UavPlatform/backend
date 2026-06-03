package com.uav.chat.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.uav.chat.pojo.dto.SessionDTO;
import com.uav.chat.pojo.entity.ChatSession;
import com.uav.chat.pojo.vo.SessionVO;
import jakarta.validation.Valid;

import java.util.List;

public interface SessionService extends IService<ChatSession> {
    void createSession(@Valid SessionDTO dto);

    void deleteSession(@Valid Long sessionId);

    List<SessionVO> listSession();
}

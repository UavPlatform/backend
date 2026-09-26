package com.uav.chat.service;

import com.uav.chat.pojo.dto.SessionDTO;
import com.uav.chat.pojo.vo.SessionVO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public interface SessionService {
    SessionVO createSession(@Valid SessionDTO dto);

    String deleteSession(@Valid Long sessionId);

    List<SessionVO> listSession();

    List<Long> getUserIdsBySessionId(@NotNull Long sessionId);
}

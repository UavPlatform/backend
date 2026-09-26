package com.uav.chat.service;

import com.uav.chat.pojo.dto.SessionDTO;
import com.uav.chat.pojo.vo.SessionVO;
import com.uav.chat.pojo.vo.TaskChatSessionVO;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public interface SessionService {
    SessionVO createSession(@Valid SessionDTO dto);

    String deleteSession(@Valid Long sessionId);

    List<SessionVO> listSession();

    List<Long> getUserIdsBySessionId(@NotNull Long sessionId);

    /**
     * 按 taskNum 聚合任务会话（TASK-BACKEND-005 / ADR-0003）。
     *
     * <p>权限：仅任务属主与该任务的应征/选定飞手（TaskApplication 存在即算，含 ACTIVE/SELECTED）
     * 可访问，否则 FORBIDDEN。任务属主看到该任务全部一对一会话（含应征飞手、applicationId、
     * 未读数）；应征飞手仅看到自己与任务属主的会话。
     *
     * @param taskNum 任务编号
     * @param userId  当前登录用户
     */
    List<TaskChatSessionVO> listTaskSessions(String taskNum, @NotNull Long userId);
}

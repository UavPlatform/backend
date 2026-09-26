package com.uav.chat.controller.webController;

import com.uav.chat.pojo.vo.TaskChatSessionVO;
import com.uav.chat.service.SessionService;
import com.uav.server.annotation.OperationLog;
import com.uav.server.annotation.RateLimiter;
import com.uav.server.result.Result;
import com.uav.server.util.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 任务会话聚合查询（TASK-BACKEND-005 / REQ-BACKEND-001 / ADR-0003）。
 *
 * <p>供订单详情内嵌聊天按 {@code taskNum} 找到与各意向飞手的会话。权限在
 * {@link SessionService#listTaskSessions} 内判定：仅任务属主与该任务的应征/选定飞手
 * 可访问，未授权 403。
 */
@Tag(name = "chat session API", description = "会话相关接口")
@RestController
@Slf4j
public class TaskChatSessionController {

    @Autowired
    private SessionService sessionService;

    @OperationLog("查询任务会话")
    @RateLimiter(limit = 30, windowSeconds = 60)
    @Operation(summary = "按任务编号查询任务会话", description = "任务属主按 taskNum 聚合与各意向飞手的一对一会话"
            + "（含对方飞手、applicationId、最后一条消息与未读数）；应征/选定飞手仅返回自己参与的会话；"
            + "非属主非应征飞手 → 403",
            parameters = {@Parameter(name = "taskNum", description = "任务编号", required = true)})
    @GetMapping("/task/{taskNum}/chat-sessions")
    public Result<List<TaskChatSessionVO>> listTaskChatSessions(@PathVariable String taskNum) {
        Long userId = UserContext.getUserId();
        return Result.success("获取成功", sessionService.listTaskSessions(taskNum, userId));
    }
}

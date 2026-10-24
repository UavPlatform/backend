package com.uav.dispatch.controller;

import com.uav.dispatch.pojo.dto.DispatchAssignmentDTO;
import com.uav.dispatch.pojo.vo.DispatchChatVO;
import com.uav.dispatch.service.DispatchChatService;
import com.uav.server.annotation.RequireRole;
import com.uav.server.result.Result;
import com.uav.server.util.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/dispatch/orders") @RequireRole({0, 1})
public class DispatchChatController {
    private final DispatchChatService chats;
    public DispatchChatController(DispatchChatService chats) { this.chats = chats; }
    @PostMapping("/{orderId}/chat")
    @Operation(summary = "打开本次接单的订单聊天", description = "REQ-SHIP-EXEC-002 / REQ-PILOT-EXEC-006；仅货主与当前飞手，服务端选择成员，按订单及指派轮次幂等；退出或重新派单后旧会话不可访问；结单只读已有会话")
    public Result<DispatchChatVO> openDispatchChat(@PathVariable String orderId, @Valid @RequestBody DispatchAssignmentDTO input) {
        return Result.success(chats.open(UserContext.getUserId(), orderId, input.assignmentVersion()));
    }
}

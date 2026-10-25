package com.uav.dispatch.controller;

import com.uav.dispatch.service.DispatchEventStreamService;
import com.uav.server.annotation.RequireRole;
import com.uav.server.util.JwtUtil;
import com.uav.server.util.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController @RequestMapping("/dispatch/events") @RequireRole({0, 1})
public class DispatchEventStreamController {
    private final DispatchEventStreamService streams;
    private final JwtUtil jwt;
    public DispatchEventStreamController(DispatchEventStreamService streams, JwtUtil jwt) {
        this.streams = streams; this.jwt = jwt;
    }
    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(operationId = "dispatchEventStream", summary = "本人订单变更实时信号",
            description = "Bearer头鉴权；ready/change/resync事件触发客户端重新读取有权限的数据，heartbeat仅保活。"
                    + "不携带订单内容或凭证，不接受URL令牌。重连ready完整同步，无历史事件重放。单连接最多60秒且不超过JWT有效期。")
    @ApiResponse(responseCode = "200", content = @Content(mediaType = MediaType.TEXT_EVENT_STREAM_VALUE,
            schema = @Schema(type = "string")))
    public SseEmitter events(@RequestHeader("Authorization") String authorization, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-cache, no-store");
        response.setHeader("X-Accel-Buffering", "no");
        long expires = jwt.extractAllClaims(authorization.substring(7)).getExpiration().getTime();
        return streams.open(UserContext.getUserId(), UserContext.getRole(), expires);
    }
}

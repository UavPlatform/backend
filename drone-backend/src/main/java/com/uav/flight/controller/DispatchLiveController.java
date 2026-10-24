package com.uav.flight.controller;

import com.uav.flight.pojo.dto.*;
import com.uav.flight.pojo.vo.*;
import com.uav.flight.service.DispatchLiveService;
import com.uav.server.annotation.RequireRole;
import com.uav.server.result.Result;
import com.uav.server.util.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/dispatch")
public class DispatchLiveController {
    private final DispatchLiveService live;
    public DispatchLiveController(DispatchLiveService live) { this.live = live; }
    @GetMapping("/orders/{orderId}/live") @RequireRole({0, 1, 2})
    @Operation(summary = "本单直播状态", description = "货主/当前飞手/管理员只读，不启动直播；未配置或心跳过期不可观看。此接口没有凭证和副作用。")
    public Result<DispatchLiveStatusVO> current(@PathVariable String orderId) {
        return Result.success(live.current(UserContext.getUserId(), UserContext.getRole(), orderId));
    }
    @PostMapping("/orders/{orderId}/live/{liveId}/watch") @RequireRole({0, 1, 2})
    @Operation(summary = "本单只读观看凭证", description = "运输中的当前连接且实际首帧已上报、心跳有效；返回绑定本次房间的120秒进房票据，仅接收视频，无发流或开关权限。观看退出不影响飞手。")
    public Result<DispatchLiveCredentialsVO> watch(@PathVariable String orderId, @PathVariable String liveId, jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return Result.success(live.watch(UserContext.getUserId(), UserContext.getRole(), orderId, liveId));
    }
    @PostMapping("/pilot/orders/{orderId}/flight-connections/{connectionId}/live") @RequireRole(1)
    @Operation(summary = "飞手主动开启本次连接直播", description = "当前飞手和接单轮次，到场/运输阶段；同requestKey幂等。新派单复用既有TRTC应用配置且需确认已启用房间权限，未配置503。此调用不代表首帧成功。")
    public Result<DispatchLiveCredentialsVO> start(@PathVariable String orderId, @PathVariable String connectionId, @Valid @RequestBody DispatchLiveStartDTO input, jakarta.servlet.http.HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return Result.success(live.start(UserContext.getUserId(), orderId, connectionId, input.requestKey()));
    }
    @PostMapping("/pilot/orders/{orderId}/flight-connections/{connectionId}/live/{liveId}/heartbeat") @RequireRole(1)
    @Operation(summary = "上报本单真实推流状态", description = "SDK发出首帧且继续发送实际图传才报RUNNING，否则STARTING；有效期20秒，过期不允许续活旧房间。")
    public Result<DispatchLiveStatusVO> heartbeat(@PathVariable String orderId, @PathVariable String connectionId, @PathVariable String liveId, @Valid @RequestBody DispatchLiveHeartbeatDTO input) {
        return Result.success(live.heartbeat(UserContext.getUserId(), orderId, connectionId, liveId, input.state()));
    }
    @PostMapping("/pilot/orders/{orderId}/flight-connections/{connectionId}/live/{liveId}/stop") @RequireRole(1)
    @Operation(summary = "飞手关闭本次直播记录", description = "本人历史房间可幂等关闭，不能关闭新房间；客户端同步停止实际推流，成功data=null。")
    public Result<Void> stop(@PathVariable String orderId, @PathVariable String connectionId, @PathVariable String liveId) {
        live.stop(UserContext.getUserId(), orderId, connectionId, liveId); return Result.success();
    }
}

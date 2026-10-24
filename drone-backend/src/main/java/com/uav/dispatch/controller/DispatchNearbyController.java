package com.uav.dispatch.controller;
import com.uav.dispatch.pojo.dto.DispatchPositionDTO;
import com.uav.dispatch.pojo.vo.*;
import com.uav.dispatch.service.*;
import com.uav.server.annotation.RequireRole;
import com.uav.server.result.Result;
import com.uav.server.util.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/dispatch") @RequireRole(1)
public class DispatchNearbyController {
    private final DispatchPositionService positions;
    private final DispatchNearbyHallService hall;
    private final DispatchNearbyService nearby;
    public DispatchNearbyController(DispatchPositionService positions, DispatchNearbyHallService hall, DispatchNearbyService nearby) {
        this.positions = positions; this.hall = hall; this.nearby = nearby;
    }
    @PutMapping("/pilot/position") @Operation(summary = "更新本人附近派单位置", description = "仅当前飞手；GCJ02真实SDK采样，拒绝模拟、过期与非有限值；只保留最新位置，不接受客户端用户ID；重放旧采样不续期")
    public Result<DispatchPositionVO> updateDispatchPosition(@Valid @RequestBody DispatchPositionDTO input) {
        return Result.success(positions.update(UserContext.getUserId(), input));
    }
    @PostMapping("/pilot/position-session") @Operation(summary = "开启新的附近位置会话", description = "仅当前飞手；立即使旧会话失效并清除旧坐标；不取得定位，不算已匹配；迟到的旧上报不能恢复已关闭会话")
    public Result<String> beginDispatchPositionSession() { return Result.success("附近位置会话已开启", positions.begin(UserContext.getUserId())); }
    @DeleteMapping("/pilot/position") @Operation(summary = "停止附近匹配并清除本人最新位置")
    public Result<Void> clearDispatchPosition(@RequestParam(required = false) String sessionKey) { positions.clear(UserContext.getUserId(), sessionKey); return Result.success(); }
    @GetMapping("/pilot/nearby-hall") @Operation(summary = "按有效位置筛选附近订单", description = "按起点直线距离筛选后分页，缺位置或位置过期返回LOCATION_REQUIRED，不降级为全部；不返回联系人或其他飞手坐标")
    public Result<DispatchNearbyHallVO> nearbyDispatchHall(@RequestParam(defaultValue = "0") @Min(0) @Max(1000000) int page) {
        return Result.success(hall.hall(UserContext.getUserId(), page));
    }
    @GetMapping("/orders/{orderId}/nearby-dispatch") @RequireRole(0)
    @Operation(summary = "本人运单的附近派单进度", description = "当前轮次实际生成站内派单通知的去重人数；不是手机推送或已读人数；等待十分钟后提醒，保持原半径，不自动取消")
    public Result<DispatchNearbyStatusVO> nearbyDispatchStatus(@PathVariable String orderId) {
        return Result.success(nearby.status(UserContext.getUserId(), orderId));
    }
}

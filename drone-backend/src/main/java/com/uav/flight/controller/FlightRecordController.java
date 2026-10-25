package com.uav.flight.controller;

import com.uav.flight.pojo.dto.*;
import com.uav.flight.pojo.vo.*;
import com.uav.flight.service.FlightRecordService;
import com.uav.server.annotation.RequireRole;
import com.uav.server.result.Result;
import com.uav.server.util.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.*;

/** REQ-SHIP-EXEC-004 / REQ-PILOT-EXEC-007. No legacy permanent equipment binding. */
@RestController
@RequestMapping("/dispatch")
public class FlightRecordController {
    private final FlightRecordService records;
    public FlightRecordController(FlightRecordService records) { this.records = records; }
    @PostMapping("/pilot/orders/{orderId}/flight-connections") @RequireRole(1)
    @Operation(summary = "登记本次实际设备连接", description = "当前到场或运输飞手及接单轮次；同请求幂等，换机结束旧连接。设备序列号同时只属于一个活动运单。未知机型不推测载重，不改变计价。")
    public Result<FlightConnectionVO> connect(@PathVariable String orderId, @Valid @RequestBody FlightConnectDTO input) {
        return Result.success(records.connect(UserContext.getUserId(), orderId, input));
    }
    @PostMapping("/pilot/orders/{orderId}/flight-connections/{connectionId}/samples") @RequireRole(1)
    @Operation(summary = "保存实际SDK采样", description = "仅当前活动连接；未知值为null，禁止合成遥测；同序号同内容幂等，冲突409。乱序保存但不回退当前状态。capturedAt为设备毫秒时间，receivedAt为服务端时间。")
    public Result<FlightSampleVO> sample(@PathVariable String orderId, @PathVariable String connectionId, @Valid @RequestBody FlightSampleDTO input) {
        return Result.success(records.sample(UserContext.getUserId(), orderId, connectionId, input));
    }
    @PostMapping("/pilot/orders/{orderId}/flight-connections/{connectionId}/samples/batch") @RequireRole(1)
    @Operation(summary = "批量上报/补传实际SDK采样", description = "djifly定时批量或断网恢复后补传，最多500条。按(连接,序号)与(机身号,采集时间)去重：已存在且内容一致计duplicates；非法、内容冲突或采集时间不在连接时段±10分钟内计rejected，客户端可丢弃；存储失败整批返回5xx，客户端保留缓存重试。连接结束后、送达确认或结单阶段仍可补传，但不更新实时状态。未知值为null。")
    public Result<FlightSampleBatchVO> batch(@PathVariable String orderId, @PathVariable String connectionId, @Valid @RequestBody FlightSampleBatchDTO input) {
        return Result.success(records.batch(UserContext.getUserId(), orderId, connectionId, input));
    }
    @PostMapping("/pilot/orders/{orderId}/flight-connections/{connectionId}/close") @RequireRole(1)
    @Operation(summary = "结束本人设备连接", description = "原飞手可幂等结束自己的连接；历史请求不能结束新连接；不控制物理飞行，成功data=null。")
    public Result<Void> close(@PathVariable String orderId, @PathVariable String connectionId) {
        records.close(UserContext.getUserId(), orderId, connectionId); return Result.success();
    }
    @GetMapping("/orders/{orderId}/flight") @RequireRole({0, 1, 2})
    @Operation(summary = "读取实际飞行状态", description = "仅货主、当前飞手或管理员。无采样字段为null；30秒未收到数据或连接结束标记stale。最后位置保留接收时间。非官方飞行安全或空域判定。")
    public Result<FlightViewVO> current(@PathVariable String orderId) {
        return Result.success(records.current(UserContext.getUserId(), UserContext.getRole(), orderId));
    }
    @GetMapping("/orders/{orderId}/flight/track") @RequireRole({0, 1, 2})
    @Operation(summary = "读取本单实际采样轨迹", description = "同飞行状态权限，after为上页nextAfter；每页200条，保持服务端保存顺序；不插值补点。")
    public Result<FlightTrackVO> track(@PathVariable String orderId, @RequestParam(defaultValue = "0") @Min(0) long after) {
        return Result.success(records.track(UserContext.getUserId(), UserContext.getRole(), orderId, after));
    }
    @GetMapping("/orders/{orderId}/flight/history") @RequireRole({0, 1, 2})
    @Operation(summary = "按时间查询历史轨迹（回放）", description = "同飞行状态权限。按设备采集时间升序，可选from/to（毫秒，含端点）；续页传上页nextCapturedAt与nextSampleId；limit 1-1000，默认500。附带本单全部连机记录。遥测保留180天，超期采样不可查，连机记录与结算数据不受影响。")
    public Result<FlightHistoryVO> history(@PathVariable String orderId,
            @RequestParam(required = false) Long from, @RequestParam(required = false) Long to,
            @RequestParam(required = false) Long afterCapturedAt, @RequestParam(required = false) Long afterSampleId,
            @RequestParam(defaultValue = "500") int limit) {
        return Result.success(records.history(UserContext.getUserId(), UserContext.getRole(), orderId, from, to, afterCapturedAt, afterSampleId, limit));
    }
}

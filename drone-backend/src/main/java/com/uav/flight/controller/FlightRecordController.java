package com.uav.flight.controller;

import com.uav.flight.pojo.vo.FlightEventPageVO;
import com.uav.flight.pojo.vo.FlightProjectionVO;
import com.uav.flight.service.FlightProjectionReader;
import com.uav.server.annotation.RequireRole;
import com.uav.server.result.Result;
import com.uav.server.util.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.bind.annotation.*;

/** REQ-SHIP-EXEC-004 / REQ-PILOT-EXEC-007 / ADR-0014: one projection read path. */
@RestController
@RequestMapping("/dispatch/orders/{orderId}/flight")
@RequireRole({0, 1, 2})
public class FlightRecordController {
    private final FlightProjectionReader records;
    public FlightRecordController(FlightProjectionReader records) { this.records = records; }

    @GetMapping
    @Operation(summary = "读取订单事件投影", description = "仅货主、当前飞手或管理员。各类型读数保留独立采集时间，未知值不合成。仅使用 v2 已接受事件；连续性与投影分开。")
    public Result<FlightProjectionVO> current(@PathVariable String orderId) {
        return Result.success(records.current(UserContext.getUserId(), UserContext.getRole(), orderId));
    }

    @GetMapping("/track")
    @Operation(summary = "增量读取位置投影", description = "只返回 POSITION，after 为上页 nextAfter；迟到位置仍有新的 changeSequence，不插值。")
    public Result<FlightEventPageVO> track(@PathVariable String orderId, @RequestParam(defaultValue = "0") long after,
            @RequestParam(defaultValue = "200") int limit) {
        return Result.success(records.track(UserContext.getUserId(), UserContext.getRole(), orderId, after, limit));
    }

    @GetMapping("/history")
    @Operation(summary = "读取事件历史投影", description = "按 capturedAt,eventId 升序；nextCapturedAt,nextEventId 为双游标。飞机筛选必须同时传 serialNumber 和 serialSource。revision 变化后可重新加载历史。")
    public Result<FlightEventPageVO> history(@PathVariable String orderId,
            @RequestParam(required = false) String serialNumber, @RequestParam(required = false) String serialSource,
            @RequestParam(required = false) Long afterCapturedAt, @RequestParam(required = false) String afterEventId,
            @RequestParam(defaultValue = "500") int limit) {
        return Result.success(records.history(UserContext.getUserId(), UserContext.getRole(), orderId, serialNumber, serialSource,
                afterCapturedAt, afterEventId, limit));
    }
}

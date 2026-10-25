package com.uav.dispatch.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** Admin supervision views of dispatch orders (list row and detail). */
public final class AdminDispatchOrderVO {
    private AdminDispatchOrderVO() { }

    @Schema(name = "AdminDispatchOrderParty", description = "订单参与方（货主或飞手）")
    public record Party(Long userId, String userName) { }

    @Schema(name = "AdminDispatchOrderRow", description = "管理端派单订单列表行")
    public record Row(String orderId, String stage, String fundsStatus, Party shipper, Party pilot,
                      BigDecimal depositAmount, BigDecimal pilotServiceFee, String executedSerialNumber,
                      String executedModelName, LocalDateTime createdAt, LocalDateTime updatedAt) { }

    @Schema(name = "AdminDispatchOrderTimelineEvent", description = "状态时间线节点（按时间升序）")
    public record TimelineEvent(String event, LocalDateTime at, Long actorUserId) { }

    @Schema(name = "AdminDispatchOrderAssignment", description = "派单（接单）记录，每次重新派单 assignmentVersion 递增")
    public record Assignment(long assignmentVersion, Party pilot, LocalDateTime acceptedAt, LocalDateTime arrivedAt,
                             LocalDateTime endedAt, String endReason) { }

    @Schema(name = "AdminDispatchOrderConnection", description = "djifly 上报的连机记录")
    public record Connection(String connectionId, long assignmentVersion, Long pilotId, String serialNumber,
                             String modelCode, String modelName, Long aircraftModelId, LocalDateTime openedAt,
                             LocalDateTime endedAt, String endReason) { }

    @Schema(name = "AdminDispatchOrderDetail", description = "管理端派单订单详情：order 为与货主端一致的订单视图（含运单字段 fields、账单 bill、实际执行无人机）")
    public record Detail(DispatchOrderVO order, Party shipper, Party pilot, List<TimelineEvent> timeline,
                         List<Assignment> assignments, List<Connection> connections) { }

}

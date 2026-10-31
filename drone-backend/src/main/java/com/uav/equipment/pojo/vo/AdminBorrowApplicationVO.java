package com.uav.equipment.pojo.vo;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.LocalDateTime;

@Schema(description = "管理端借机申请")
public record AdminBorrowApplicationVO(
        @Schema(description = "申请/借用 ID") String borrowId,
        @Schema(description = "状态 PENDING/APPROVED/REJECTED/RETURNED") String status,
        @Schema(description = "飞手 ID") Long pilotId,
        @Schema(description = "飞手用户名") String pilotName,
        @Schema(description = "申请机型 ID") Long aircraftModelId,
        @Schema(description = "申请机型名称") String modelName,
        @Schema(description = "可选关联订单 ID", nullable = true) String orderId,
        @Schema(description = "申请时间") LocalDateTime appliedAt,
        @Schema(description = "出借机身（通过后）", nullable = true) String droneId,
        @Schema(description = "机身号（通过后）", nullable = true) String serialNumber,
        @Schema(description = "借出时间", nullable = true) LocalDateTime borrowedAt,
        @Schema(description = "归还时间", nullable = true) LocalDateTime returnedAt,
        @Schema(description = "驳回原因", nullable = true) String rejectReason,
        @Schema(description = "飞手当前是否已有已借出飞机（特批参考）") boolean pilotHasActiveLoan,
        @Schema(description = "该机型当前在库启用飞机数") long inStockCount) { }

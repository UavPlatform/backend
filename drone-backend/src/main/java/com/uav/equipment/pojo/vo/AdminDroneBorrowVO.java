package com.uav.equipment.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

@Schema(description = "管理端无人机借还记录")
public record AdminDroneBorrowVO(
        @Schema(description = "借用记录 ID") String borrowId,
        @Schema(description = "无人机登记 ID") String droneId,
        @Schema(description = "借用飞手 ID") Long pilotId,
        @Schema(description = "借用飞手用户名") String pilotName,
        @Schema(description = "借出时间") LocalDateTime borrowedAt,
        @Schema(description = "归还时间；未归还为 null", nullable = true) LocalDateTime returnedAt) { }

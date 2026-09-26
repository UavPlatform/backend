package com.uav.task.pojo.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.uav.server.enums.ApplicationStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 应征记录视图（REQ-BACKEND-001：按 taskNum 返回应征飞手、机型、系统报价、应征时间）。
 *
 * <p>用于飞手应征响应与用户应征列表两个端点；{@code quotedAmount} 为系统报价
 * （ADR-0003：成交价必须严格等于该值）。
 */
@Data
@NoArgsConstructor
@Schema(description = "飞手应征记录（含平台系统报价）")
public class TaskApplicationVO {

    @Schema(description = "应征记录 ID")
    private Long applicationId;

    @Schema(description = "任务编号")
    private String taskNum;

    @Schema(description = "应征飞手用户 ID")
    private Long riderId;

    @Schema(description = "应征飞手用户名")
    private String riderName;

    @Schema(description = "应征使用的机型 ID")
    private Long aircraftModelId;

    @Schema(description = "机型型号编码（如 FC30、M350RTK）")
    private String modelCode;

    @Schema(description = "机型显示名（如 DJI FlyCart 30）")
    private String aircraftModelName;

    @Schema(description = "机型最大载重（kg）")
    private BigDecimal maxPayloadKg;

    @Schema(description = "平台系统报价（元）：成交价必须严格等于该值，不允许改价")
    private BigDecimal quotedAmount;

    @Schema(description = "应征状态（ACTIVE=应征中；SELECTED/CLOSED 为用户选定阶段语义）")
    private ApplicationStatus status;

    @Schema(description = "应征时间（首次应征时间）")
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime appliedAt;
}

package com.uav.task.pojo.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户选定应征并下单请求（POST /task/{taskNum}/select-rider，ADR-0003 决定 3）。
 *
 * <p>不含金额字段：成交价恒等于该应征的系统报价 {@code quotedAmount}（不允许改价）。
 */
@Data
@Schema(description = "用户选定应征并下单（金额由服务端锁定为系统报价，不接受客户端金额字段）")
public class SelectRiderDto {

    @Schema(description = "用户选定的应征记录 ID", requiredMode = Schema.RequiredMode.REQUIRED)
    private Long applicationId;

    @Schema(description = "约定作业时间（yyyy-MM-dd HH:mm:ss），下单即视为用户确认该时间",
            requiredMode = Schema.RequiredMode.REQUIRED)
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime scheduledTime;
}

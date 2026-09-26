package com.uav.task.pojo.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 飞手应征入参：只提交「哪个任务 + 本次使用的机型」（REQ-BACKEND-001 API 与数据 3）。
 *
 * <p><b>不接受客户端金额字段（ADR-0003 决定 2「平台计价 SSOT」）</b>：请求体里携带
 * {@code price}、{@code quotedAmount}、{@code reward} 等任何金额字段一律忽略
 * （类级 {@code @JsonIgnoreProperties(ignoreUnknown = true)}，行为与 Jackson 全局配置无关），
 * 报价仅由服务端 {@code TransportPriceCalculator} 计算并持久化。
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class RiderApplyDto {

    /** 任务编号（task.task_num）。 */
    private String taskNum;

    /** 本次应征使用的机型（{@code GET /api/aircraft-models} 返回的 id），须与绑定设备映射一致。 */
    private Long aircraftModelId;

    /**
     * 飞手报价（元）。可选，缺省取平台基准价（{@code TransportPriceCalculator} 输出）。
     *
     * <p>平台基准价为主、协商为辅：报价须落在基准价的
     * [{@code MIN_NEGOTIATED_RATE}, {@code MAX_NEGOTIATED_RATE}] 区间内，
     * 越界抛 INVALID_PARAM —— 平台不干涉定价自由，但防止恶意刷价。
     * 用户选定应征时按该值锁定成交价（totalAmount 严格等于 quotedAmount）。
     */
    private BigDecimal quotedAmount;
}

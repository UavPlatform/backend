package com.uav.map.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 地图配置。只下发高德 Web/SDK key，绝不下发 securityKey（安全密钥 jscode）。
 * Web 端使用高德安全代理模式：securityKey 只配置在服务端反向代理（deploy/Caddyfile 的 /_AMapService），
 * 前端设置 {@code window._AMapSecurityConfig = { serviceHost }}。
 */
@Schema(description = "地图配置（不含 securityKey）")
public record MapConfigVO(
        @Schema(description = "高德地图 key", requiredMode = Schema.RequiredMode.REQUIRED) String key,
        @Schema(description = "Web 端高德安全代理地址（_AMapSecurityConfig.serviceHost）；未配置时为 null",
                nullable = true) String serviceHost) {}

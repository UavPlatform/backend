package com.uav.map.controller;

import com.uav.map.pojo.vo.MapConfigVO;
import com.uav.server.annotation.RequireRole;
import com.uav.server.config.AmapConfig;
import com.uav.server.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 地图配置：需求方、飞手、管理员均可取 key；securityKey 留在服务端（Web 走高德安全代理）。 */
@RestController
@RequestMapping("/config")
@RequireRole({0, 1, 2})
@Tag(name = "配置")
public class MapConfigController {

    private final AmapConfig amapConfig;

    public MapConfigController(AmapConfig amapConfig) {
        this.amapConfig = amapConfig;
    }

    @Operation(operationId = "getMapConfig", summary = "获取地图配置",
            description = "返回高德 key 与 Web 安全代理地址；不返回 securityKey。响应带 Cache-Control: private")
    @GetMapping("/map")
    public Result<MapConfigVO> getMapConfig(HttpServletResponse response) {
        response.setHeader("Cache-Control", "private");
        String host = StringUtils.hasText(amapConfig.getServiceHost()) ? amapConfig.getServiceHost().trim() : null;
        return Result.success(new MapConfigVO(amapConfig.getKey(), host));
    }
}

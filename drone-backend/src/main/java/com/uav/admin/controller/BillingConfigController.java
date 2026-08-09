package com.uav.admin.controller;

import com.uav.billing.pojo.entity.BillConfig;
import com.uav.billing.service.BillConfigService;
import com.uav.server.annotation.OperationLog;
import com.uav.server.annotation.RequireRole;
import com.uav.server.enums.Role;
import com.uav.server.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Admin Billing API", description = "管理端计费配置")
@RestController
@RequestMapping("/admin/billing")
@Slf4j
@RequiredArgsConstructor
@RequireRole({Role.ADMIN})
public class BillingConfigController {

    private final BillConfigService billConfigService;

    @OperationLog("查看计费配置")
    @Operation(summary = "计费配置列表", description = "获取全部计费配置项（费率、阶梯、夜间时段、砍价下限等），即时生效")
    @GetMapping("/config")
    public Result<List<BillConfig>> listConfig() {
        return Result.success(billConfigService.listAll());
    }

    @OperationLog("更新计费配置")
    @Operation(summary = "更新计费配置",
            description = "按 configKey 更新配置值（如 perKmFee、nightRate），下次计价即时生效",
            parameters = {@Parameter(name = "configKey", description = "配置项 key", required = true),
                    @Parameter(name = "value", description = "新配置值", required = true)})
    @PutMapping("/config/{configKey}")
    public Result<BillConfig> updateConfig(@PathVariable String configKey,
                                           @RequestParam String value) {
        billConfigService.updateConfig(configKey, value);
        BillConfig updated = billConfigService.listAll().stream()
                .filter(c -> c.getConfigKey().equals(configKey))
                .findFirst()
                .orElse(null);
        return Result.success("配置已更新", updated);
    }
}

package com.uav.dispatch.controller;

import com.uav.dispatch.pojo.dto.WaybillDraftDTO;
import com.uav.dispatch.pojo.vo.DispatchConfigVO;
import com.uav.dispatch.pojo.vo.WaybillDraftVO;
import com.uav.dispatch.service.DispatchSettingsService;
import com.uav.dispatch.service.WaybillDraftService;
import com.uav.server.annotation.RequireRole;
import com.uav.server.result.Result;
import com.uav.server.util.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Dispatch Draft API", description = "派单固定配置与账号运单草稿，不创建订单或收款")
@RestController
@RequestMapping("/dispatch")
public class DispatchController {
    private final DispatchSettingsService settings;
    private final WaybillDraftService drafts;

    public DispatchController(DispatchSettingsService settings, WaybillDraftService drafts) {
        this.settings = settings;
        this.drafts = drafts;
    }

    @Operation(summary = "派单固定金额配置", description = "需登录；仅返回平台定金和按单飞手服务费，非总价或预估价；orderingEnabled取决于服务端评估数据和受限模拟支付是否可用")
    @GetMapping("/config")
    public Result<DispatchConfigVO> config() {
        return Result.success(settings.current());
    }

    @Operation(summary = "读取我的运单草稿", description = "仅需求方本人；无草稿时 success=true,data=null；照片仍只保存在本机，不包含于账号草稿")
    @RequireRole(0)
    @GetMapping("/waybill-draft")
    public Result<WaybillDraftVO> draft() {
        return Result.success(drafts.get(UserContext.getUserId()));
    }

    @Operation(summary = "保存我的运单草稿", description = "允许部分文本输入；不创建任务、订单或支付；首次保存 expectedRevision 省略，后续传最新 revision；并发或旧版本写入返回409/WAYBILL_DRAFT_CONFLICT；拒绝未知字段、价格和本机照片路径")
    @RequireRole(0)
    @PutMapping("/waybill-draft")
    public Result<WaybillDraftVO> save(@Valid @RequestBody WaybillDraftDTO input) {
        return Result.success(drafts.save(UserContext.getUserId(), input));
    }
}

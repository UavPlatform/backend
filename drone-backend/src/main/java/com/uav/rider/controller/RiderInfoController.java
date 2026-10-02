package com.uav.rider.controller;

import com.uav.rider.pojo.dto.RiderInfoDto;
import com.uav.rider.pojo.vo.RiderInfoVO;
import com.uav.rider.service.RiderInfoService;
import com.uav.server.annotation.OperationLog;
import com.uav.server.annotation.RequireRole;
import com.uav.server.result.Result;
import com.uav.server.util.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 飞手本人资料与驾驶资质（REQ-APP-001「飞手：资料维护」；替换原占位 {@code POST /info/edit}）。
 *
 * <p>仅飞手（role=1）可访问：未登录 401、非飞手 403。身份证号只写不读——出参只含脱敏值。
 */
@RequireRole(1)
@Tag(name = "Rider Info API", description = "飞手资料与驾驶资质维护")
@RestController
@RequestMapping("/rider/info")
@RequiredArgsConstructor
public class RiderInfoController {

    private final RiderInfoService riderInfoService;

    @Operation(summary = "查看本人资料",
            description = "飞手查看本人资料、证件有效期（身份证号脱敏）与驾驶资质（机型 × 执照等级）；"
                    + "从未保存资料时除用户名外均为空")
    @GetMapping
    public Result<RiderInfoVO> getInfo() {
        return Result.success(riderInfoService.getInfo(UserContext.getUserId()));
    }

    @OperationLog("编辑飞手资料")
    @Operation(summary = "编辑本人资料",
            description = "局部更新：字段为 null 不修改。身份证号校验格式与校验位；证件有效期不得早于今天；"
                    + "qualifications 传数组时按机型做集合差异（缺失删除、新机型新增、等级变化更新，[] 清空），"
                    + "机型须为启用机型（否则 AIRCRAFT_MODEL_NOT_FOUND）。参数错误 400 INVALID_PARAM")
    @PatchMapping
    public Result<RiderInfoVO> editInfo(@Valid @RequestBody RiderInfoDto dto) {
        return Result.success("保存成功", riderInfoService.editInfo(UserContext.getUserId(), dto));
    }
}

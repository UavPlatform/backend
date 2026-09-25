package com.uav.rider.controller;

import com.uav.rider.pojo.dto.RiderInfoDto;
import com.uav.server.result.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Rider Info API", description = "飞手资料维护（占位实现，尚未接入持久化）")
@RestController
// TODO(yx9926): 路径疑似笔误 —— 其余飞手接口均为 /rider/xxx，此处缺 /rider 前缀。
//  该路径已固化进 OpenAPI 契约（spec/openapi/drone-backend.openapi.json 的 "/info/edit"），
//  改动会触发契约漂移门禁，故本次合并仅标注、未改动。
//  另：feat/feed 分支上已有本接口的真实实现（GET + PATCH /rider/info，
//  见 RiderInfoService/Impl，已随本次合并进入本仓库但暂无人调用），
//  待该分支并入 main 后可一并收敛。  —— wmc 合并时标注，2026-09-25
@RequestMapping("/info")
public class RiderInfoController {

    // TODO(yx9926): 占位实现，仅返回成功，未落库。
    //  真实实现在 feat/feed 分支；RiderInfoService/Impl/VO 已在本仓库中，
    //  待该分支并入 main 后接线即可。  —— wmc 合并时标注，2026-09-25
    @Operation(summary = "编辑本人资料", description = "飞手编辑个人资料；当前为占位实现，仅返回成功")
    @PostMapping("/edit")
    public Result<Void> editSelfInfo(@RequestBody RiderInfoDto riderInfoDto) {
        return Result.success();
    }
}

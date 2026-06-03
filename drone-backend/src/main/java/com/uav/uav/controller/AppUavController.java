package com.uav.uav.controller;

import com.uav.uav.pojo.dto.UavDto;
import com.uav.server.result.Result;
import com.uav.uav.pojo.vo.UavVo;
import com.uav.server.annotation.OperationLog;
import com.uav.server.annotation.SkipJwt;
import com.uav.uav.service.AppUavService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@Tag(name = "AppUav API")
@RestController
@RequestMapping("/appUav")
@Slf4j
public class AppUavController {

    @Autowired
    private AppUavService appUavService;

    @SkipJwt
    @OperationLog("新增无人机")
    @Operation(
            summary = "新增无人机",
            description = "添加新的无人机信息",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "新增成功",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(
                                            type = "object",
                                            example = "{\"success\": true, \"code\": 200, \"message\": \"操作成功\", "
                                                    + "\"data\": {\"id\": 1, \"uavName\": \"无人机1\"}}"
                                    )
                            )
                    ),
                    @ApiResponse(
                            responseCode = "400",
                            description = "参数错误",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(
                                            type = "object",
                                            example = "{\"success\": false, \"code\": 400, "
                                                    + "\"errorCode\": \"INVALID_PARAM\", "
                                                    + "\"message\": \"无人机信息缺失\"}"
                                    )
                            )
                    )
            }
    )
    @PostMapping("/add")
    public Result<UavVo> addUav(@RequestBody UavDto uavDto) {
        UavVo uavVo = appUavService.tryToAddUav(uavDto);
        return Result.success(uavVo);
    }

    @OperationLog("更新无人机")
    @Operation(
            summary = "更新无人机信息",
            description = "更新无人机的基本信息，包括名称、在线状态等",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "更新成功",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(
                                            type = "object",
                                            example = "{\"success\": true, \"code\": 200, \"message\": \"操作成功\"}"
                                    )
                            )
                    ),
                    @ApiResponse(
                            responseCode = "404",
                            description = "无人机不存在",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(
                                            type = "object",
                                            example = "{\"success\": false, \"code\": 404, "
                                                    + "\"errorCode\": \"UAV_NOT_FOUND\", "
                                                    + "\"message\": \"无人机不存在\"}"
                                    )
                            )
                    )
            }
    )
    @PutMapping("/update")
    public Result<Void> updateOnlineStatus(@RequestBody UavDto uavDto) {
        appUavService.updateUav(uavDto);
        return Result.success("更新成功");
    }
}

package com.drone.controller.uav;

import com.drone.pojo.entity.UserRecord;
import com.drone.pojo.result.Result;
import com.drone.pojo.vo.uav.UavVo;
import com.drone.pojo.vo.uav.WebUavStatusVo;
import com.drone.pojo.vo.user.UserRecordsVO;
import com.drone.server.annotation.OperationLog;
import com.drone.service.WebUavService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Tag(name = "WebUav API")
@RestController
@RequestMapping("/webUav")
@Slf4j
public class WebUavController {

    @Autowired
    private WebUavService webUavService;

    @OperationLog("查询无人机列表")
    @Operation(
            summary = "查询无人机",
            description = "获取所有的无人机列表",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "查询成功",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(
                                            type = "object",
                                            example = "{\"success\": true, \"code\": 200, \"message\": \"操作成功\", "
                                                    + "\"data\": [{\"id\": 1, \"uavName\": \"无人机1\"}, "
                                                    + "{\"id\": 2, \"uavName\": \"无人机2\"}]}"
                                    )
                            )
                    )
            }
    )
    @GetMapping("/getUav")
    public Result<List<UavVo>> getUav(@RequestParam(required = false, defaultValue = "false") Boolean onlineOnly) {
        List<UavVo> list = (onlineOnly != null && onlineOnly)
                ? webUavService.getOnlineUav()
                : webUavService.getUav();
        return Result.success(list);
    }

    @OperationLog("查询无人机状态")
    @Operation(
            summary = "查询单台无人机实时状态",
            description = "根据设备ID查询平台侧最近一次收到的无人机状态",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "查询成功",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(
                                            type = "object",
                                            example = "{\"success\": true, \"code\": 200, \"message\": \"操作成功\", "
                                                    + "\"data\": {\"djiId\": \"123456\", \"wsConnected\": true, "
                                                    + "\"liveState\": \"RUNNING\", \"latestStatus\": {\"battery\": 85}}}"
                                    )
                            )
                    ),
                    @ApiResponse(
                            responseCode = "404",
                            description = "无人机或状态不存在"
                    )
            }
    )
    @GetMapping("/status")
    public Result<WebUavStatusVo> getUavStatus(@RequestParam String deviceId) {
        WebUavStatusVo status = webUavService.getUavStatus(deviceId);
        return Result.success(status);
    }

    @OperationLog("查询用户观看记录")
    @Operation(
            summary = "查询用户个人观看记录（管理员权限）",
            description = "根据用户名查询用户的直播观看记录",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "查询成功",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(
                                            type = "object",
                                            example = "{\"success\": true, \"code\": 200, \"message\": \"获取成功\", "
                                                    + "\"data\": {\"records\": [{\"id\": 1, \"djiId\": \"123456\", "
                                                    + "\"startTime\": \"2026-03-27T10:00:00\", "
                                                    + "\"endTime\": \"2026-03-27T11:00:00\"}], "
                                                    + "\"total\": 5, \"totalPages\": 1}}"
                                    )
                            )
                    )
            }
    )
    @GetMapping("/getRecord")
    public Result<UserRecordsVO> getUserRecord(@RequestParam String userName,
                                                @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "10") int size) {
        Pageable pageable = PageRequest.of(page, size);
        Page<UserRecord> recordPage = webUavService.getUserRecord(userName, pageable);

        List<UserRecordsVO.RecordItem> records = recordPage.getContent().stream().map(r -> {
            UserRecordsVO.RecordItem item = new UserRecordsVO.RecordItem();
            item.setId(r.getId());
            item.setDjiId(r.getDjiId());
            item.setStartTime(r.getStart_time());
            item.setEndTime(r.getEnd_time());
            return item;
        }).toList();

        UserRecordsVO vo = new UserRecordsVO();
        vo.setRecords(records);
        vo.setTotal(recordPage.getTotalElements());
        vo.setTotalPages(recordPage.getTotalPages());
        return Result.success("获取成功", vo);
    }
}

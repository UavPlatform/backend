package com.uav.uav.controller;

import com.uav.user.pojo.entity.UserRecord;
import com.uav.server.result.Result;
import com.uav.server.service.AmapService;
import com.uav.uav.pojo.vo.GpsPointVO;
import com.uav.uav.pojo.vo.UavVo;
import com.uav.uav.pojo.vo.WebUavStatusVo;
import com.uav.user.pojo.vo.UserRecordsVO;
import com.uav.server.annotation.OperationLog;
import com.uav.uav.service.UavStatusService;
import com.uav.server.util.UserContext;
import com.uav.uav.service.WebUavService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.uav.user.controller.UserController.getUserRecordsVOResult;

@Tag(name = "WebUav API")
@RestController
@RequestMapping("/webUav")
@Slf4j
public class WebUavController {

    @Autowired
    private WebUavService webUavService;

    @Autowired
    private UavStatusService uavStatusService;

    @Autowired
    private AmapService amapService;

    @OperationLog("查询无人机列表")
    @Operation(
            summary = "查询无人机",
            description = "获取所有的无人机列表",
            responses = {
                    @ApiResponse(responseCode = "200", description = "查询成功")
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
                    @ApiResponse(responseCode = "200", description = "查询成功"),
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

    // ---- GPS 轨迹与位置 ----

    @OperationLog("查询飞行轨迹")
    @Operation(
            summary = "查询订单飞行轨迹",
            description = "根据订单号查询无人机执行该订单时的 GPS 轨迹，按时间升序排列",
            parameters = {@Parameter(name = "orderNum", description = "订单号", required = true)})
    @GetMapping("/trajectory")
    public Result<List<GpsPointVO>> getTrajectory(@RequestParam String orderNum) {
        List<GpsPointVO> points = uavStatusService.getTrajectoryByOrderNum(orderNum).stream()
                .map(GpsPointVO::from)
                .toList();
        return Result.success(points);
    }

    @OperationLog("查询无人机位置")
    @Operation(
            summary = "查询无人机实时位置",
            description = "优先内存实时数据，内存无数据时降级为 DB 最近 2 分钟内的记录",
            parameters = {
                    @Parameter(name = "uavId", description = "无人机 ID（与 deviceId 二选一）"),
                    @Parameter(name = "deviceId", description = "设备 ID（与 uavId 二选一）")
            })
    @GetMapping("/position")
    public Result<GpsPointVO> getPosition(@RequestParam(required = false) Long uavId,
                                          @RequestParam(required = false) String deviceId) {
        GpsPointVO point = uavStatusService.getLatestPosition(uavId, deviceId);
        return Result.success(point);
    }

    // ---- 高德地图服务端 API ----

    @OperationLog("逆地理编码")
    @Operation(
            summary = "坐标转地址",
            description = "调用高德逆地理编码 API，将经纬度转为格式化地址（结果缓存 4 位小数精度）",
            parameters = {
                    @Parameter(name = "lng", description = "经度", required = true),
                    @Parameter(name = "lat", description = "纬度", required = true)
            })
    @GetMapping("/reverseGeocode")
    public Result<Map<String, Object>> reverseGeocode(@RequestParam double lng,
                                                       @RequestParam double lat) {
        String address = amapService.reverseGeocode(lng, lat);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("longitude", lng);
        result.put("latitude", lat);
        result.put("address", address);
        return Result.success(result);
    }

    @OperationLog("查询用户观看记录")
    @Operation(
            summary = "查询观看记录（本人）",
            description = "普通用户固定查询自己的直播观看记录；管理员可传 userName 代查任意用户",
            responses = {
                    @ApiResponse(responseCode = "200", description = "查询成功")
            }
    )
    @GetMapping("/getRecord")
    public Result<UserRecordsVO> getUserRecord(@RequestParam(required = false) String userName,
                                                @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "10") int size) {
        // P0-5：普通用户一律只能查自己的观看记录；仅管理员可显式指定 userName 代查
        Integer role = UserContext.getRole();
        String targetUser;
        if (role != null && role == 2 && StringUtils.hasText(userName)) {
            targetUser = userName;
        } else {
            targetUser = UserContext.getUsername();
        }

        Pageable pageable = PageRequest.of(page, size);
        Page<UserRecord> recordPage = webUavService.getUserRecord(targetUser, pageable);

        return getUserRecordsVOResult(recordPage);
    }
}

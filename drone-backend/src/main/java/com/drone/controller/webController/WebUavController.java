package com.drone.controller.webController;

import com.drone.pojo.vo.UavVo;
import com.drone.service.WebUavService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@Tag(name = "WebUav API")
@RestController
@RequestMapping("/webUav")
@Slf4j
public class WebUavController {

    @Autowired
    private WebUavService webUavService;

    /**
     * 获取所有的无人机
     * @return
     */
    @Operation(
            summary = "查询无人机",
            description = "获取所有的无人机列表",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "查询成功（可能有数据或无数据）",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(
                                            type = "object",
                                            // 直接用字符串数组，兼容所有版本
                                            examples = {
                                                    "{\"success\": true, \"uav\": [{\"id\": 1, \"uavName\": \"无人机1\"}, {\"id\": 2, \"uavName\": \"无人机2\"}]}",
                                                    "{\"success\": false, \"message\": \"暂时没有无人机在线\"}"
                                            }
                                    )
                            )
                    ),
                    @ApiResponse(
                            responseCode = "400",
                            description = "查询失败",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(
                                            type = "object",
                                            example = "{\"success\": false, \"message\": \"查询失败\"}"
                                    )
                            )
                    )
            }
    )
    @GetMapping("/getUav")
    public ResponseEntity<Map<String, Object>> getUav(){
        log.info("手机端查询目前所有的无人机");
        Map<String, Object> result = new HashMap<>();
        try {
            UavVo[] onlineUavVos = webUavService.getUav();
            if (onlineUavVos == null || onlineUavVos.length == 0) {
                result.put("success", false);
                result.put("message", "系统暂时没有录入无人机");
            } else {
                result.put("success", true);
                result.put("uav", onlineUavVos);
            }
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("查询无人机失败:{}", e.getMessage());
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.status(400).body(result);
        }
    }
}

package com.drone.controller.appController;

import com.drone.pojo.dto.UavDto;
import com.drone.pojo.vo.UavVo;
import com.drone.service.AppUavService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;


@Tag(name = "AppUav API")
@RestController
@RequestMapping("/appUav")
@Slf4j
public class AppUavController {

    @Autowired
    private AppUavService appUavService;

    /**
     * 新增无人机
     * @param uavDto 无人机信息
     * @return 新增结果
     */
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
                                            example = "{\"success\": true, \"id\": 1, \"name\": \"无人机1\"}"
                                    )
                            )
                    ),
                    @ApiResponse(
                            responseCode = "400",
                            description = "新增失败",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(
                                            type = "object",
                                            example = "{\"success\": false, \"message\": \"无人机名字已存在\"}"
                                    )
                            )
                    ),
                    @ApiResponse(
                            responseCode = "401",
                            description = "参数错误",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(
                                            type = "object",
                                            example = "{\"success\": false, \"message\": \"无人机名称或在线状态为空\"}"
                                    )
                            )
                    )
            }
    )
    @PostMapping("/add")
    public ResponseEntity<Map<String, Object>> addUav(@RequestBody UavDto uavDto){
        log.info("新增无人机请求：UavName:{}", uavDto.getUavName());
        Map<String, Object> result = new HashMap<>();
        try{
            if (uavDto.getUavName() == null || uavDto.getOnlineStatus() == null) {
                result.put("success", false);
                result.put("message", "无人机名称或在线状态为空");
                return ResponseEntity.status(401).body(result);
            }

            UavVo uavVo = appUavService.tryToAddUav(uavDto);
            result.put("id", uavVo.getId());
            result.put("name", uavVo.getUavName());
            result.put("success", true);
            return ResponseEntity.ok(result);
        }catch(Exception e){
            log.error("添加无人机失败:{}", e.getMessage());
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.status(400).body(result);
        }
    }

    @Operation(
            summary = "查询所有无人机",
            description = "获取所有无人机信息",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "查询成功",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(
                                            type = "object",
                                            example = "{\"success\": true, \"uav\": [{\"id\": 1, \"uavName\": \"无人机1\"}, {\"id\": 2, \"uavName\": \"无人机2\"}]}"
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
    public ResponseEntity<Map<String, Object>> getAllUav(){
        log.info("手机端查询无人机");
        Map<String, Object> result = new HashMap<>();
        try {
            UavVo[] uavVos = appUavService.getAllUav();
            if (uavVos == null || uavVos.length == 0) {
                result.put("success", false);
                result.put("message", "暂时没有无人机");
                return ResponseEntity.ok(result);
            } else {
                result.put("success", true);
                result.put("uav", uavVos);
                return ResponseEntity.ok(result);
            }
        } catch (Exception e) {
            log.error("查询无人机失败:{}", e.getMessage());
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.status(400).body(result);
        }
    }
}
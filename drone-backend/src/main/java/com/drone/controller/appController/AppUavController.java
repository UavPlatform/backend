package com.drone.controller.appController;

import com.drone.pojo.dto.UavDto;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Null;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;


@Tag(name = "WebUav API")
@RestController
@RequestMapping("/appUav")
@Slf4j
public class AppDroneController {

    @PostMapping("/add")
    public ResponseEntity<Map<String, Object>> addUav(@RequestBody UavDto uavDto){

        log.info("新增无人机请求：UavName:{}",uavDto.getUavName());
        Map<String, Object> result = new HashMap<>();
        try{
            if (uavDto.getUavName() ==null || uavDto.getOnlineStatus()==null) {
                result.put("success", false);
                result.put("message", "无人机名称或在线状态为空");
                return ResponseEntity.status(401).body(result);
            }
            return ResponseEntity.ok(result);
        }catch(Exception e){
            log.error("添加无人机失败:{}", e.getMessage());
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.status(400).body(result);
        }

    }

}

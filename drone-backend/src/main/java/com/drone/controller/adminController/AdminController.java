package com.drone.controller.adminController;

import com.drone.pojo.dto.AdminDto;
import com.drone.pojo.entity.Admin;
import com.drone.server.annotation.SkipJwt;
import com.drone.server.exception.BusinessException;
import com.drone.server.exception.UnauthorizedException;
import com.drone.server.util.JwtUtil;
import com.drone.service.AdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@Tag(name = "Admin Auth API")
@RestController
@RequestMapping("/admin")
@Slf4j
public class AdminController {

    @Autowired
    private AdminService adminService;
    
    @Autowired
    private JwtUtil jwtUtil;

    @Operation(
            summary = "管理员登录",
            description = "管理员登录接口",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "登录成功",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(
                                            type = "object",
                                            example = "{\"success\": true, \"data\": {\"token\": \"eyJhbGciOiJIUzI1NiJ9...\", \"admin\": {\"id\": 1, \"name\": \"admin\"}}, \"message\": \"登录成功\"}"
                                    )
                            )
                    ),
                    @ApiResponse(
                            responseCode = "401",
                            description = "用户名或密码错误",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(
                                            type = "object",
                                            example = "{\"success\": false, \"message\": \"用户名或密码错误\"}"
                                    )
                            )
                    )
            }
    )
    @SkipJwt
    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> adminLogin(@RequestBody AdminDto adminDto) {
        log.info("管理端登录Name：{}", adminDto.getName());
        try {
            Admin admin = adminService.tryToLogin(adminDto);
            String token = jwtUtil.generateToken(admin.getName());
            
            Map<String, Object> adminInfo = new HashMap<>();
            adminInfo.put("id", admin.getId());
            adminInfo.put("name", admin.getName());
            adminInfo.put("phoneNumber", admin.getPhoneNumber());
            
            Map<String, Object> data = new HashMap<>();
            data.put("token", token);
            data.put("admin", adminInfo);
            
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("data", data);
            response.put("message", "登录成功");
            
            return ResponseEntity.ok(response);
        } catch (UnauthorizedException e) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(response);
        } catch (BusinessException e) {
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.status(e.getHttpStatus()).body(response);
        } catch (Exception e) {
            log.error("登录失败: {}", e.getMessage());
            Map<String, Object> response = new HashMap<>();
            response.put("success", false);
            response.put("message", "登录失败，请稍后重试");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }
}
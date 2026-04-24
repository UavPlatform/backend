package com.drone.controller.adminController;

import com.drone.pojo.dto.AdminDto;
import com.drone.pojo.entity.Admin;
import com.drone.pojo.result.Result;
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
    public Result<Map<String, Object>> adminLogin(@RequestBody AdminDto adminDto) {
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

            return Result.success("登录成功", data);
        } catch (UnauthorizedException e) {
            return Result.fail(401, e.getMessage());
        } catch (BusinessException e) {
            return Result.fail(e.getHttpStatus().value(), e.getMessage());
        } catch (Exception e) {
            log.error("登录失败: {}", e.getMessage());
            return Result.fail(500, "登录失败，请稍后重试");
        }
    }
}
package com.drone.controller;

import com.drone.pojo.dto.UserLoginDto;
import com.drone.service.LoginService;
import com.drone.server.util.JwtUtil;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@Tag(name = "Login API")
@RestController
@RequestMapping("/user")
@Slf4j
public class LoginController {

    @Autowired
    private LoginService loginService;

    @Autowired
    private JwtUtil jwtUtil;

    /**
     * 登录接口
     * @param userLoginDto
     * @return
     */
    @Operation(
            summary = "用户登录",
            description = "验证用户ID和密码，成功返回JWT令牌，失败返回错误信息",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "登录成功",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(
                                            type = "object",
                                            example = "{\"success\": true, \"token\": \"eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...\", \"refreshToken\": \"eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...\"}"
                                    )
                            )
                    ),
                    @ApiResponse(
                            responseCode = "401",
                            description = "登录失败",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(
                                            type = "object",
                                            example = "{\"success\": false, \"message\": \"ID或密码错误\"}"
                                    )
                            )
                    )
            }
    )
    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody UserLoginDto userLoginDto) {
        log.info("用户登录请求: ID={}", userLoginDto.getId());
        Map<String, Object> result = new HashMap<>();
        boolean success = loginService.tryToLogin(userLoginDto);
        if (success) {
            String idStr = userLoginDto.getId().toString();
            String token = jwtUtil.generateToken(idStr);
            String refreshToken = jwtUtil.generateRefreshToken(idStr);
            result.put("success", true);
            result.put("token", token);
            result.put("refreshToken", refreshToken);
            log.info("用户登录成功: ID={}", userLoginDto.getId());
            return ResponseEntity.ok(result);
        } else {
            result.put("success", false);
            result.put("message", "ID或密码错误");
            log.warn("用户登录失败: ID={}, 原因: 密码错误", userLoginDto.getId());
            return ResponseEntity.status(401).body(result);
        }
    }

    /**
     * 固定一小时刷新令牌，到时间自动请求
     * @param refreshToken
     * @return
     */
    @Operation(
            summary = "刷新令牌",
            description = "使用Refresh-Token刷新获取新的Access-Token"
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "刷新成功",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(
                                    type = "object",
                                    example = "{\"success\": true, \"token\": \"eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...\"}"
                            )
                    )
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "刷新失败",
                    content = @Content(
                            mediaType = "application/json",
                            schema = @Schema(
                                    type = "object",
                                    example = "{\"success\": false, \"message\": \"无效的刷新令牌\"}"
                            )
                    )
            )
    })
    @PostMapping("/refresh")
    public ResponseEntity<Map<String, Object>> refreshToken(@RequestHeader("Refresh-Token") String refreshToken) {
        log.info("刷新令牌请求");
        Map<String, Object> result = new HashMap<>();
        try {
            String newToken = jwtUtil.refreshAccessToken(refreshToken);
            result.put("success", true);
            result.put("token", newToken);
            log.info("令牌刷新成功");
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "无效的刷新令牌");
            log.warn("令牌刷新失败: 原因={}", e.getMessage());
            return ResponseEntity.status(401).body(result);
        }
    }



}
package com.drone.controller.webController;

import com.drone.pojo.dto.UserLoginDto;
import com.drone.pojo.dto.UserRegisterDto;
import com.drone.pojo.vo.RegisterVo;
import com.drone.service.LoginService;
import com.drone.server.util.JwtUtil;
import com.drone.service.RegisterService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@Tag(name = "Login API")
@RestController
@RequestMapping("/user")
@Slf4j
public class UserController {

    @Autowired
    private LoginService loginService;

    @Autowired
    private RegisterService registerService;

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
        //暂时未加密密码，后期添加
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
    /**
     * 用户注册接口
     * @param userRegisterDto 注册信息
     * @return 注册结果
     */
    @Operation(
            summary = "用户注册",
            description = "用户注册，返回结果和用户信息",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "注册成功",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(
                                            type = "object",
                                            example = "{\"success\": true, \"userId\": 123, \"userName\": \"test\"}"
                                    )
                            )
                    ),
                    @ApiResponse(
                            responseCode = "400",
                            description = "注册失败",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(
                                            type = "object",
                                            example = "{\"success\": false, \"message\": \"用户名已存在\"}"
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
                                            example = "{\"success\": false, \"message\": \"ID或密码为空\"}"
                                    )
                            )
                    )
            }
    )
    @PostMapping("/register")
    public ResponseEntity<Map<String, Object>> registerUser(@RequestBody UserRegisterDto userRegisterDto) {
        log.info("注册请求: userName={}", userRegisterDto.getUserName());
        Map<String, Object> result = new HashMap<>();
        try {
            if (userRegisterDto.getPassword() == null || userRegisterDto.getUserName() == null) {
                result.put("success", false);
                result.put("message", "用户名或密码为空");
                return ResponseEntity.status(401).body(result);
            }

            RegisterVo registerVo = registerService.tryToRegister(userRegisterDto);
            log.info("用户注册成功 ID：{} Name:{}", registerVo.getId(), registerVo.getUserName());
            result.put("success", true);
            result.put("userId", registerVo.getId());
            result.put("userName", registerVo.getUserName());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("注册失败:{}", e.getMessage());
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.status(400).body(result);
        }
    }


}
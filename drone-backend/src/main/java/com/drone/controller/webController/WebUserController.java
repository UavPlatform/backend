package com.drone.controller.webController;

import com.drone.mapper.UserRecordRepository;
import com.drone.pojo.dto.UserLoginDto;
import com.drone.pojo.dto.UserRegisterDto;
import com.drone.pojo.entity.User;
import com.drone.pojo.entity.UserRecord;
import com.drone.pojo.vo.RegisterVo;
import com.drone.server.util.UserContext;
import com.drone.service.LoginService;
import com.drone.server.annotation.SkipJwt;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@Tag(name = "Authentication and Authorization API")
@RestController
@RequestMapping("/user")
@Slf4j
public class WebUserController {

    @Autowired
    private LoginService loginService;

    @Autowired
    private RegisterService registerService;

    @Autowired
    private JwtUtil jwtUtil;
    
    @Autowired
    private UserRecordRepository userRecordRepository;

    /**
     * 登录接口
     * @param userLoginDto
     * @return
     */
    @SkipJwt
    @Operation(
            summary = "用户登录",
            description = "验证用户名和密码，成功返回JWT令牌，失败返回错误信息",
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
                                            example = "{\"success\": false, \"message\": \"用户名或密码错误\"}"
                                    )
                            )
                    )
            }
    )
    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody UserLoginDto userLoginDto) {
        //暂时未加密密码，后期添加
        log.info("用户登录请求: userName={}", userLoginDto.getUserName());
        Map<String, Object> result = new HashMap<>();
        User user = loginService.tryToLogin(userLoginDto);
        if (user != null) {
            String idStr = user.getId().toString();
            String token = jwtUtil.generateToken(idStr);
            String refreshToken = jwtUtil.generateRefreshToken(idStr);
            result.put("success", true);
            result.put("token", token);
            result.put("refreshToken", refreshToken);
            log.info("用户登录成功: userName={}, id={}", user.getUserName(), user.getId());
            return ResponseEntity.ok(result);
        } else {
            result.put("success", false);
            result.put("message", "用户名或密码错误");
            log.warn("用户登录失败: userName={}, 原因: 密码错误", userLoginDto.getUserName());
            return ResponseEntity.status(401).body(result);
        }
    }

    /**
     * 固定一小时刷新令牌，到时间自动请求
     * @param refreshToken
     * @return
     */
    @SkipJwt
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
    @SkipJwt
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

    /**
     * 查询用户直播记录
     * @param page 页码，从0开始
     * @param size 每页记录数
     * @return 用户的直播记录列表
     */
    @Operation(
            summary = "查询用户直播记录",
            description = "获取当前登录用户的直播观看记录，支持分页",
            responses = {
                    @ApiResponse(
                            responseCode = "200",
                            description = "查询成功",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(
                                            type = "object",
                                            example = "{\"success\": true, \"records\": [{\"id\": 1, \"userName\": \"test\", \"djiId\": \"123456\", \"start_time\": \"2026-03-27T10:00:00\", \"end_time\": \"2026-03-27T11:00:00\"}], \"total\": 10, \"page\": 0, \"size\": 5, \"totalPages\": 2}"
                                    )
                            )
                    ),
                    @ApiResponse(
                            responseCode = "401",
                            description = "未登录",
                            content = @Content(
                                    mediaType = "application/json",
                                    schema = @Schema(
                                            type = "object",
                                            example = "{\"success\": false, \"message\": \"用户未登录\"}"
                                    )
                            )
                    )
            }
    )
    @GetMapping("/records")
    public ResponseEntity<Map<String, Object>> getLiveRecords(@RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "10") int size) {
        log.info("查询用户直播记录: page={}, size={}", page, size);
        Map<String, Object> result = new HashMap<>();
        
        try {
            // 获取当前登录用户
            String userName = UserContext.getUsername();
            if (userName == null) {
                result.put("success", false);
                result.put("message", "用户未登录");
                return ResponseEntity.status(401).body(result);
            }
            
            // 创建分页参数
            Pageable pageable = PageRequest.of(page, size);
            
            // 查询用户直播记录
            Page<UserRecord> recordPage = userRecordRepository.findAllByUserName(userName, pageable);
            result.put("success", true);
            result.put("records", recordPage.getContent());
            result.put("total", recordPage.getTotalElements());
            result.put("page", recordPage.getNumber());
            result.put("size", recordPage.getSize());
            result.put("totalPages", recordPage.getTotalPages());
            log.info("查询用户直播记录成功: userName={}, 记录数={}, 总记录数={}", userName, recordPage.getContent().size(), recordPage.getTotalElements());
            return ResponseEntity.ok(result);
        } catch (Exception e) {
            log.error("查询用户直播记录失败: {}", e.getMessage());
            result.put("success", false);
            result.put("message", e.getMessage());
            return ResponseEntity.status(400).body(result);
        }
    }

}

package com.uav.user.controller;

import com.uav.user.mapper.UserRecordRepository;
import com.uav.user.pojo.dto.UserLoginDto;
import com.uav.user.pojo.dto.UserRegisterDto;
import com.uav.user.pojo.entity.User;
import com.uav.user.pojo.entity.UserRecord;
import com.uav.server.result.Result;
import com.uav.user.pojo.vo.RegisterVo;
import com.uav.user.pojo.vo.TokenRefreshVO;
import com.uav.user.pojo.vo.UserLoginVO;
import com.uav.user.pojo.vo.UserRecordsVO;
import com.uav.server.annotation.OperationLog;
import com.uav.server.annotation.RateLimiter;
import com.uav.server.annotation.SkipJwt;
import com.uav.server.util.JwtUtil;
import com.uav.server.util.UserContext;
import com.uav.user.service.UserService;
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
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Authentication and Authorization API")
@RestController
@RequestMapping("/user")
@Slf4j
public class WebUserController {

    @Autowired
    private UserService userService;

    @Autowired
    private JwtUtil jwtUtil;

    @Autowired
    private UserRecordRepository userRecordRepository;

    @SkipJwt
    @OperationLog("登录")
    @RateLimiter(limit = 5, windowSeconds = 60)
    @Operation(
            summary = "用户登录",
            description = "验证用户名和密码，成功返回 JWT 令牌",
            responses = {
                    @ApiResponse(responseCode = "200", description = "登录成功",
                            content = @Content(mediaType = "application/json",
                                    schema = @Schema(type = "object",
                                            example = "{\"success\": true, \"code\": 200, \"message\": \"操作成功\", "
                                                    + "\"data\": {\"token\": \"eyJ...\", \"refreshToken\": \"eyJ...\"}}"))),
                    @ApiResponse(responseCode = "401", description = "用户名或密码错误",
                            content = @Content(mediaType = "application/json",
                                    schema = @Schema(type = "object",
                                            example = "{\"success\": false, \"code\": 401, \"errorCode\": \"INVALID_PARAM\", \"message\": \"用户名或密码错误\"}")))
            }
    )
    @PostMapping("/login")
    public Result<UserLoginVO> login(@RequestBody UserLoginDto userLoginDto) {
        User user = userService.tryToLogin(userLoginDto);
        UserContext.setUser(user.getId(), user.getUserName(), user.getRole());

        UserLoginVO vo = new UserLoginVO(
                jwtUtil.generateToken(user.getId(), user.getUserName(), user.getRole()),
                jwtUtil.generateRefreshToken(user.getId(), user.getUserName(), user.getRole())
        );
        return Result.success(vo);
    }

    @SkipJwt
    @OperationLog("刷新令牌")
    @Operation(
            summary = "刷新令牌",
            description = "使用 Refresh-Token 获取新的 Access-Token",
            responses = {
                    @ApiResponse(responseCode = "200", description = "刷新成功",
                            content = @Content(mediaType = "application/json",
                                    schema = @Schema(type = "object",
                                            example = "{\"success\": true, \"code\": 200, \"message\": \"操作成功\", "
                                                    + "\"data\": {\"token\": \"eyJ...\"}}"))),
                    @ApiResponse(responseCode = "401", description = "刷新失败",
                            content = @Content(mediaType = "application/json",
                                    schema = @Schema(type = "object",
                                            example = "{\"success\": false, \"code\": 401, \"errorCode\": \"UNAUTHORIZED\", \"message\": \"无效的刷新令牌\"}")))
            }
    )
    @PostMapping("/refresh")
    public Result<TokenRefreshVO> refreshToken(@RequestHeader("Refresh-Token") String refreshToken) {
        TokenRefreshVO vo = new TokenRefreshVO(jwtUtil.refreshAccessToken(refreshToken));
        return Result.success(vo);
    }

    @SkipJwt
    @OperationLog("注册")
    @RateLimiter(limit = 3, windowSeconds = 60)
    @Operation(
            summary = "用户注册",
            description = "用户注册，返回用户信息",
            responses = {
                    @ApiResponse(responseCode = "200", description = "注册成功",
                            content = @Content(mediaType = "application/json",
                                    schema = @Schema(type = "object",
                                            example = "{\"success\": true, \"code\": 200, \"message\": \"操作成功\", "
                                                    + "\"data\": {\"id\": 123, \"userName\": \"test\"}}"))),
                    @ApiResponse(responseCode = "400", description = "注册失败",
                            content = @Content(mediaType = "application/json",
                                    schema = @Schema(type = "object",
                                            example = "{\"success\": false, \"code\": 400, \"errorCode\": \"INVALID_PARAM\", \"message\": \"用户名已存在\"}")))
            }
    )
    @PostMapping("/register")
    public Result<RegisterVo> registerUser(@RequestBody UserRegisterDto userRegisterDto) {
        RegisterVo registerVo = userService.tryToRegister(userRegisterDto);
        UserContext.setUser(registerVo.id(), registerVo.userName(), 0);
        return Result.success(registerVo);
    }

    @OperationLog("查询直播记录")
    @Operation(
            summary = "查询用户直播记录",
            description = "获取当前登录用户的直播观看记录，支持分页",
            responses = {
                    @ApiResponse(responseCode = "200", description = "查询成功",
                            content = @Content(mediaType = "application/json",
                                    schema = @Schema(type = "object",
                                            example = "{\"success\": true, \"code\": 200, \"message\": \"获取成功\", "
                                                    + "\"data\": {\"records\": [{\"id\": 1, \"djiId\": \"xxx\", "
                                                    + "\"startTime\": \"2026-05-31T10:00:00\", \"endTime\": \"2026-05-31T11:00:00\"}], "
                                                    + "\"total\": 1, \"totalPages\": 1}}"))),
                    @ApiResponse(responseCode = "401", description = "未登录",
                            content = @Content(mediaType = "application/json",
                                    schema = @Schema(type = "object",
                                            example = "{\"success\": false, \"code\": 401, \"errorCode\": \"UNAUTHORIZED\", \"message\": \"Missing token\"}")))
            }
    )
    @GetMapping("/records")
    public Result<UserRecordsVO> getLiveRecords(@RequestParam(defaultValue = "0") int page,
                                                  @RequestParam(defaultValue = "10") int size) {
        String userName = UserContext.getUsername();
        Pageable pageable = PageRequest.of(page, size);
        Page<UserRecord> recordPage = userRecordRepository.findAllByUserName(userName, pageable);

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

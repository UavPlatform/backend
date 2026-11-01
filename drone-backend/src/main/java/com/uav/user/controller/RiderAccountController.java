package com.uav.user.controller;

import com.uav.user.pojo.dto.RiderRegisterDto;
import com.uav.user.pojo.entity.User;
import com.uav.user.pojo.vo.UserLoginVO;
import com.uav.user.service.RiderRegisterService;
import com.uav.server.annotation.OperationLog;
import com.uav.server.annotation.RateLimiter;
import com.uav.server.annotation.SkipJwt;
import com.uav.server.result.Result;
import com.uav.server.util.JwtUtil;
import com.uav.server.util.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Rider Register API", description = "飞手注册接口")
@RestController
@RequestMapping("/rider")
public class RiderAccountController {

    @Autowired
    private RiderRegisterService riderRegisterService;

    @Autowired
    private JwtUtil jwtUtil;

    @SkipJwt
    @OperationLog("飞手注册")
    @RateLimiter(limit = 3, windowSeconds = 60)
    @Operation(summary = "飞手注册", description = "飞手注册账号（不登记无人机），成功返回 JWT 令牌")
    @PostMapping("/register")
    public Result<UserLoginVO> register(@RequestBody RiderRegisterDto dto) {
        User user = riderRegisterService.register(dto);
        UserContext.setUser(user.getId(), user.getUserName(), user.getRole());
        UserLoginVO vo = new UserLoginVO(
                jwtUtil.generateToken(user.getId(), user.getUserName(), user.getRole()),
                jwtUtil.generateRefreshToken(user.getId(), user.getUserName(), user.getRole())
        );
        return Result.success("飞手注册成功", vo);
    }
}

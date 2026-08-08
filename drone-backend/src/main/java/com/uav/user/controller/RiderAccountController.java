package com.uav.user.controller;

import com.uav.user.pojo.dto.RiderRegisterDto;
import com.uav.user.pojo.entity.RiderUav;
import com.uav.user.pojo.entity.User;
import com.uav.user.pojo.vo.UserLoginVO;
import com.uav.user.service.RiderUavService;
import com.uav.user.service.RiderRegisterService;
import com.uav.server.annotation.OperationLog;
import com.uav.server.annotation.RateLimiter;
import com.uav.server.annotation.SkipDroneCheck;
import com.uav.server.annotation.SkipJwt;
import com.uav.server.result.Result;
import com.uav.server.util.JwtUtil;
import com.uav.server.util.UserContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Tag(name = "Rider Register API", description = "飞手注册接口")
@SkipDroneCheck
@RestController
@RequestMapping("/rider")
public class RiderAccountController {

    @Autowired
    private RiderRegisterService riderRegisterService;

    @Autowired
    private RiderUavService riderUavService;

    @Autowired
    private JwtUtil jwtUtil;

    @SkipJwt
    @OperationLog("飞手注册")
    @RateLimiter(limit = 3, windowSeconds = 60)
    @Operation(summary = "飞手注册", description = "飞手注册并绑定无人机，成功返回 JWT 令牌")
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

    @OperationLog("绑定无人机")
    @Operation(summary = "绑定无人机", description = "飞手绑定新的无人机")
    @PostMapping("/drone/bind")
    public Result<Void> bindDrone(@RequestParam String djiId) {
        Long userId = UserContext.getUserId();
        riderUavService.bindDrone(userId, djiId);
        return Result.success("无人机绑定成功");
    }

    @OperationLog("解绑无人机")
    @Operation(summary = "解绑无人机", description = "飞手解绑已绑定的无人机")
    @DeleteMapping("/drone/unbind")
    public Result<Void> unbindDrone(@RequestParam String djiId) {
        Long userId = UserContext.getUserId();
        riderUavService.unbindDrone(userId, djiId);
        return Result.success("无人机解绑成功");
    }

    @OperationLog("查看无人机列表")
    @Operation(summary = "我的无人机", description = "飞手查看自己绑定的无人机列表")
    @GetMapping("/drone/list")
    public Result<List<RiderUav>> listDrones() {
        Long userId = UserContext.getUserId();
        return Result.success(riderUavService.listDrones(userId));
    }
}

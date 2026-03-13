package com.drone.controller;

import com.drone.pojo.dto.UserLoginDto;
import com.drone.service.LoginService;
import com.drone.server.util.JwtUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/user")
public class LoginController {

    @Autowired
    private LoginService loginService;

    @Autowired
    private JwtUtil jwtUtil;

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody UserLoginDto userLoginDto) {
        Map<String, Object> result = new HashMap<>();
        boolean success = loginService.tryToLogin(userLoginDto);
        if (success) {
            String idStr = userLoginDto.getId().toString();
            String token = jwtUtil.generateToken(idStr);
            String refreshToken = jwtUtil.generateRefreshToken(idStr);
            result.put("success", true);
            result.put("token", token);
            result.put("refreshToken", refreshToken);
        } else {
            result.put("success", false);
            result.put("message", "ID或密码错误");
        }
        return result;
    }

    /**
     *
     * @param refreshToken
     * @return
     */
    @PostMapping("/refresh")
    public Map<String, Object> refreshToken(@RequestHeader("Refresh-Token") String refreshToken) {
        Map<String, Object> result = new HashMap<>();
        try {
            String newToken = jwtUtil.refreshAccessToken(refreshToken);
            result.put("success", true);
            result.put("token", newToken);
        } catch (Exception e) {
            result.put("success", false);
            result.put("message", "无效的刷新令牌");
        }
        return result;
    }
}
package com.drone.controller.auth;

import com.drone.pojo.dto.AdminDto;
import com.drone.pojo.entity.Admin;
import com.drone.pojo.result.Result;
import com.drone.pojo.vo.auth.AdminLoginVO;
import com.drone.server.annotation.OperationLog;
import com.drone.server.annotation.SkipJwt;
import com.drone.server.util.JwtUtil;
import com.drone.server.util.UserContext;
import com.drone.service.AdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Admin Auth API")
@RestController
@RequestMapping("/admin")
public class AdminController {

    @Autowired
    private AdminService adminService;

    @Autowired
    private JwtUtil jwtUtil;

    @SkipJwt
    @OperationLog("管理员登录")
    @Operation(
            summary = "管理员登录",
            description = "管理员登录接口",
            responses = {
                    @ApiResponse(responseCode = "200", description = "登录成功",
                            content = @Content(mediaType = "application/json",
                                    schema = @Schema(type = "object",
                                            example = "{\"success\": true, \"code\": 200, \"message\": \"登录成功\", "
                                                    + "\"data\": {\"token\": \"eyJ...\", \"admin\": {\"id\": 1, \"name\": \"admin\"}}}"))),
                    @ApiResponse(responseCode = "401", description = "用户名或密码错误",
                            content = @Content(mediaType = "application/json",
                                    schema = @Schema(type = "object",
                                            example = "{\"success\": false, \"code\": 401, \"errorCode\": \"INVALID_PARAM\", \"message\": \"用户名或密码错误\"}")))
            }
    )
    @PostMapping("/login")
    public Result<AdminLoginVO> adminLogin(@RequestBody AdminDto adminDto) {
        Admin admin = adminService.tryToLogin(adminDto);
        UserContext.setUsername(admin.getName());

        AdminLoginVO.AdminInfo adminInfo = new AdminLoginVO.AdminInfo();
        adminInfo.setId(admin.getId());
        adminInfo.setName(admin.getName());
        adminInfo.setPhoneNumber(admin.getPhoneNumber());

        AdminLoginVO vo = new AdminLoginVO();
        vo.setToken(jwtUtil.generateToken(admin.getName()));
        vo.setAdmin(adminInfo);

        return Result.success("登录成功", vo);
    }
}

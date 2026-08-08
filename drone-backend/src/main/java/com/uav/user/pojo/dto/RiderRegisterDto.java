package com.uav.user.pojo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

@Data
@Schema(description = "飞手注册参数")
public class RiderRegisterDto implements Serializable {

    @Schema(description = "用户名")
    private String userName;

    @Schema(description = "密码")
    private String password;

    @Schema(description = "无人机DJI ID")
    private String djiId;
}

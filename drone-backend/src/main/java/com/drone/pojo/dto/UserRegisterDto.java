package com.drone.pojo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
@Schema(description = "Parameters for Register Request(注册参数)")
public class UserRegisterDto {

    @Schema(description = "userName")
    private String userName;

    @Schema(description = "passWord")
    private String password;
}

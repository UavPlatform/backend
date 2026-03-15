package com.drone.pojo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;

@Data
@Schema(description = "Parameters for Login Request(登录参数)")
public class UserLoginDto implements Serializable {

    @Schema(description = "id")
    private Long id;
    @Schema(description = "password")
    private String password;
}
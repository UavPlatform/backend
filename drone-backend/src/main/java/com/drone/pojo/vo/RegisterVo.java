package com.drone.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

@Data
public class RegisterVo {

    @Schema(description = "id")
    private Long id;

    @Schema(description = "userName")
    private String userName;
    
    public RegisterVo(Long id, String userName) {
        this.id = id;
        this.userName = userName;
    }

    public RegisterVo() {
    }
}

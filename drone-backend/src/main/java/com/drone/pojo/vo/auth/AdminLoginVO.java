package com.drone.pojo.vo.auth;

import lombok.Data;

@Data
public class AdminLoginVO {
    private String token;
    private AdminInfo admin;

    @Data
    public static class AdminInfo {
        private Long id;
        private String name;
        private String phoneNumber;
    }
}

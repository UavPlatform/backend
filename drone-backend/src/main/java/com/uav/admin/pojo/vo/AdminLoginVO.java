package com.uav.admin.pojo.vo;

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

package com.uav.server.enums;

/**
 * 用户角色常量（User.role 字段）：
 * 0 = C 端普通用户，1 = 飞手，2 = 管理员
 * 注解参数（如 @RequireRole）要求编译期常量，故用 int 常量类而非枚举
 */
public final class Role {

    private Role() {
    }

    /** C 端普通用户（app 注册） */
    public static final int USER = 0;

    /** 飞手（djifly 注册） */
    public static final int RIDER = 1;

    /** 管理员（后台） */
    public static final int ADMIN = 2;
}

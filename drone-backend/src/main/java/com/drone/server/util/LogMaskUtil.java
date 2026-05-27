package com.drone.server.util;

/**
 * 日志脱敏工具
 */
public class LogMaskUtil {

    public static String maskUserName(String userName) {
        if (userName == null || userName.length() <= 2) {
            return "***";
        }
        return userName.charAt(0) + "***" + userName.charAt(userName.length() - 1);
    }
}

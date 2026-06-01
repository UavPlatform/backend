package com.drone.server.util;

import org.mindrot.jbcrypt.BCrypt;


public class PasswordUtil {

    private static final int ROUNDS = 12;

    /** 加密明文密码，返回 60 字符 BCrypt 哈希 */
    public static String hash(String plainPassword) {
        String salt = BCrypt.gensalt(ROUNDS);
        return BCrypt.hashpw(plainPassword, salt);
    }

    public static boolean matches(String plainPassword, String hashedPassword) {
        return BCrypt.checkpw(plainPassword, hashedPassword);
    }
}

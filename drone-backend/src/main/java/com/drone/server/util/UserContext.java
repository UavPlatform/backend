package com.drone.server.util;

//存储当前登录用户
public class UserContext {

    private static final ThreadLocal<String> currentUser = new ThreadLocal<>();

    public static void setUsername(String username) {
        currentUser.set(username);
    }

    public static String getUsername() {
        return currentUser.get();
    }

    public static void clear() {
        currentUser.remove();
    }
}
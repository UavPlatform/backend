package com.drone.server.util;

public class UserContext {

    private static final ThreadLocal<Long> currentUserId = new ThreadLocal<>();
    private static final ThreadLocal<String> currentUsername = new ThreadLocal<>();
    private static final ThreadLocal<Integer> currentRole = new ThreadLocal<>();

    public static void setUser(Long userId, String username, Integer role) {
        currentUserId.set(userId);
        currentUsername.set(username);
        currentRole.set(role);
    }

    public static Long getUserId() {
        return currentUserId.get();
    }

    public static String getUsername() {
        return currentUsername.get();
    }

    public static Integer getRole() {
        return currentRole.get();
    }

    public static void clear() {
        currentUserId.remove();
        currentUsername.remove();
        currentRole.remove();
    }
}
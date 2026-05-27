package com.drone.server.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;

public class RouteIdGenerator {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    /** 航线业务前缀 */
    public static final String ROUTE_PREFIX = "RT";

    private static final int RANDOM_MIN = 1000;
    private static final int RANDOM_MAX = 10000;
    private static final int USER_PART_LENGTH = 4;

    /**
     * 格式：[业务前缀] + [17位时间戳] + [4位用户名开头] + [4位随机数]
     */
    public static String generate(String userName) {
        String timePart = LocalDateTime.now().format(TIME_FORMATTER);

        String userPart = userName.length() >= USER_PART_LENGTH
                ? userName.substring(0, USER_PART_LENGTH)
                : String.format("%-" + USER_PART_LENGTH + "s", userName).replace(' ', '0');

        int randomPart = ThreadLocalRandom.current().nextInt(RANDOM_MIN, RANDOM_MAX);

        return ROUTE_PREFIX + timePart + userPart + randomPart;
    }
}

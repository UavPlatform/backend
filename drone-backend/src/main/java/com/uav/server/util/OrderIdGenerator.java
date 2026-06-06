package com.uav.server.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;

public class OrderIdGenerator {

    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    public static final String ORDER_PREFIX = "MO";

    private static final int RANDOM_MIN = 1000;
    private static final int RANDOM_MAX = 10000;
    private static final int USER_PART_LENGTH = 4;

    public static String generate(Long userId) {
        String timePart = LocalDateTime.now().format(TIME_FORMATTER);

        String userPart = String.format("%0" + USER_PART_LENGTH + "d",
                userId != null ? Math.abs(userId) % 10000 : 0);

        int randomPart = ThreadLocalRandom.current().nextInt(RANDOM_MIN, RANDOM_MAX);

        return ORDER_PREFIX + timePart + userPart + randomPart;
    }
}

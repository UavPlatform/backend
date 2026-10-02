package com.uav.rider.support;

import java.util.Locale;

/**
 * 居民身份证号（GB 11643-1999，18 位）规范化、校验与脱敏。
 *
 * <p>身份证号属敏感个人信息：服务端只保存规范化后的原值，任何接口（含本人与监管端）只回显
 * {@link #mask} 结果；校验失败的提示文案不回显原值。
 */
public final class IdNumbers {

    private static final int[] WEIGHTS = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};
    private static final char[] CHECK_CODES = {'1', '0', 'X', '9', '8', '7', '6', '5', '4', '3', '2'};

    private IdNumbers() {
    }

    /** 去首尾空白并把末位 x 统一为大写 X；null 原样返回。 */
    public static String normalize(String raw) {
        return raw == null ? null : raw.trim().toUpperCase(Locale.ROOT);
    }

    /** 18 位格式 + 校验码均正确才为 true（入参应先 {@link #normalize}）。 */
    public static boolean isValid(String idNumber) {
        if (idNumber == null || !idNumber.matches("\\d{17}[\\dX]")) {
            return false;
        }
        int sum = 0;
        for (int i = 0; i < 17; i++) {
            sum += (idNumber.charAt(i) - '0') * WEIGHTS[i];
        }
        return CHECK_CODES[sum % 11] == idNumber.charAt(17);
    }

    /**
     * 脱敏：保留前 3 位与后 4 位，其余以 {@code *} 替代（如 {@code 110***********002X}）；
     * null/空返回 null，过短的值整体打码。
     */
    public static String mask(String idNumber) {
        if (idNumber == null || idNumber.isEmpty()) {
            return null;
        }
        if (idNumber.length() <= 7) {
            return "*".repeat(idNumber.length());
        }
        return idNumber.substring(0, 3)
                + "*".repeat(idNumber.length() - 7)
                + idNumber.substring(idNumber.length() - 4);
    }
}

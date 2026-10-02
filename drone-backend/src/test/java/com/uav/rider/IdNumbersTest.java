package com.uav.rider;

import com.uav.rider.support.IdNumbers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 身份证号规范化/校验/脱敏（单元层，R1 不启动 Spring）：敏感数据出参口径的 SSOT。
 */
class IdNumbersTest {

    @Test
    @DisplayName("校验：18 位 + GB 11643 校验码；末位 x 规范化为 X")
    void validatesChecksum() {
        assertThat(IdNumbers.isValid("11010519491231002X")).isTrue();
        assertThat(IdNumbers.isValid(IdNumbers.normalize(" 11010519491231002x "))).isTrue();
        assertThat(IdNumbers.isValid("110105194912310021")).as("校验位错误").isFalse();
        assertThat(IdNumbers.isValid("11010519491231002")).as("17 位").isFalse();
        assertThat(IdNumbers.isValid("1101051949123100AX")).as("含字母").isFalse();
        assertThat(IdNumbers.isValid(null)).isFalse();
    }

    @Test
    @DisplayName("脱敏：保留前 3 后 4，中间全部打码；null/空返回 null；短值整体打码")
    void masksMiddleDigits() {
        assertThat(IdNumbers.mask("11010519491231002X")).isEqualTo("110***********002X");
        assertThat(IdNumbers.mask(null)).isNull();
        assertThat(IdNumbers.mask("")).isNull();
        assertThat(IdNumbers.mask("1234567")).isEqualTo("*******");
    }
}

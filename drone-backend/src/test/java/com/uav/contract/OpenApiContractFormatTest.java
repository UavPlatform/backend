package com.uav.contract;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.support.OpenApiContract;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Contract file formatting is platform-independent (LF, one trailing newline). */
class OpenApiContractFormatTest {

    @Test
    void prettyUsesLfAndSingleTrailingNewline() throws Exception {
        var node = new ObjectMapper().readTree("{\"b\":2,\"a\":1}");
        String text = OpenApiContract.pretty(node);
        assertThat(text).doesNotContain("\r");
        assertThat(text).endsWith("\n").doesNotEndWith("\n\n");
    }
}

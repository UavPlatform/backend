package com.uav.flight.event;

import org.erdtman.jcs.JsonCanonicalizer;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonCanonTest {
    @Test
    void sortsKeysAndMatchesTheCandidateLibraryOnValidIntegers() throws Exception {
        byte[] raw = "{\"b\":1,\"a\":2,\"s\":\"\\u00e9\"}".getBytes(StandardCharsets.UTF_8);
        String canonical = JsonCanon.canonicalText(JsonCanon.parse(raw));
        assertThat(canonical).isEqualTo("{\"a\":2,\"b\":1,\"s\":\"é\"}");
        assertThat(new JsonCanonicalizer(new String(raw, StandardCharsets.UTF_8)).getEncodedString()).isEqualTo(canonical);
        assertThat(JsonCanon.canonicalText(JsonCanon.parse("{\"s\":\"é\"}".getBytes(StandardCharsets.UTF_8))))
                .isEqualTo(JsonCanon.canonicalText(JsonCanon.parse("{\"s\":\"\\u00e9\"}".getBytes(StandardCharsets.UTF_8))));
    }

    @Test
    void doesNotNormalizeUnicode() {
        String composed = JsonCanon.canonicalText(JsonCanon.parse("{\"é\":1}".getBytes(StandardCharsets.UTF_8)));
        String combining = JsonCanon.canonicalText(JsonCanon.parse("{\"e\\u0301\":1}".getBytes(StandardCharsets.UTF_8)));
        assertThat(composed).isNotEqualTo(combining);
    }

    @Test
    void escapesLineSeparatorsAndNotSolidus() {
        String canonical = JsonCanon.canonicalText(JsonCanon.parse("{\"s\":\"a/b\\u2028\"}".getBytes(StandardCharsets.UTF_8)));
        assertThat(canonical).isEqualTo("{\"s\":\"a/b\\u2028\"}");
    }

    @Test
    void rejectsParserHolesTheJcsLibraryAccepts() {
        assertRejected("{\"a\":null,\"a\":1}", "DUPLICATE_KEY");
        assertRejected("{\"a\":\"\\u0061\",\"\\u0061\":1}", "DUPLICATE_KEY");
        assertRejected("{\"a\":01}", "NUMBER");
        assertRejected("{\"a\":-0}", "NUMBER");
        assertRejected("{\"a\":1.0}", "NUMBER");
        assertRejected("{\"a\":1e2}", "NUMBER");
        assertRejected("{\"a\":9007199254740993}", "NUMBER");
        assertRejected("{\"s\":\"\\ud800\"}", "SURROGATE");
        assertRejected("{\"a\":1} trailing", "TRAILING");
        byte[] bom = new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, '{', '}'};
        assertThatThrownBy(() -> JsonCanon.parse(bom)).isInstanceOf(EventFormatException.class).hasMessage("BOM");
        assertThatThrownBy(() -> JsonCanon.parse(new byte[] {(byte) 0xC3, 0x28})).isInstanceOf(EventFormatException.class).hasMessage("UTF8");
    }

    private static void assertRejected(String json, String token) {
        assertThatThrownBy(() -> JsonCanon.parse(json.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(EventFormatException.class).hasMessage(token);
    }
}

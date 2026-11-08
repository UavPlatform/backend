package com.uav.flight.event;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class EventReconciliationSchemaTest {
    private final EventReconciliationSchema schema = new EventReconciliationSchema();

    @Test
    void limitIsCheckedAsALongBeforeItBecomesAnInt() {
        assertThat(schema.parse(body("1")).limit()).isEqualTo(1);
        assertThat(schema.parse(body("200")).limit()).isEqualTo(200);
        assertRejected("0");
        assertRejected("201");
        assertRejected("2147483648");
        assertRejected("4294967297");
        assertThatThrownBy(() -> schema.parse(body("1.5"))).isInstanceOf(EventFormatException.class);
    }

    private static void assertRejected(String limit) {
        assertThatThrownBy(() -> new EventReconciliationSchema().parse(body(limit)))
                .isInstanceOf(EventFormatException.class).hasMessage("LIMIT");
    }

    private static byte[] body(String limit) {
        String json = "{\"schemaVersion\":2,\"chainId\":\"33333333-3333-4333-8333-333333333333\","
                + "\"validationVersion\":null,\"fromHeight\":1,\"toHeight\":1,\"cursor\":null,"
                + "\"missingCursor\":null,\"limit\":" + limit + ",\"prefix\":null}";
        return json.getBytes(StandardCharsets.UTF_8);
    }
}

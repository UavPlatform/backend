package com.uav.dispatch;

import com.uav.dispatch.service.DispatchAssessmentGateway;
import com.uav.dispatch.pojo.vo.DispatchEvaluationVO;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class DispatchTestData {
    private DispatchTestData() { }
    static Map<String, String> fields() {
        var f = new LinkedHashMap<String, String>();
        f.putAll(Map.of("category", "普通", "weightMin", "2", "length", "30", "width", "20",
                "height", "10", "count", "1", "packaging", "纸箱", "lifting", "绑带", "timeMode", "now"));
        for (String side : List.of("start", "end")) {
            for (var entry : Map.of("lat", "28.2", "lng", side.equals("start") ? "113.0" : "113.1",
                    "site", "空地", "landing", "yes", "contact", "测试联系人", "phone", "13800000000").entrySet())
                f.put(side + "." + entry.getKey(), entry.getValue());
        }
        return f;
    }
    static Map<String, Object> waybill(Map<String, String> fields) {
        return Map.of("schemaVersion", 1, "step", 4, "fields", fields);
    }
    @TestConfiguration(proxyBeanMethods = false)
    static class AssessmentFixture {
        @Bean @Primary DispatchAssessmentGateway testOnlyAssessment() {
            return new DispatchAssessmentGateway() {
                public boolean available() { return true; }
                public DispatchEvaluationVO evaluate(Map<String, String> fields) {
                    // Synthetic risk data is installed only by an explicit test import, never in main sources.
                    return new DispatchEvaluationVO(fields.get("category").equals("液体") ? "PROHIBITED" : "CAUTION",
                            "TEST_FIXTURE_NOT_REAL_AIRSPACE", new BigDecimal("1000"), new BigDecimal("5"),
                            new BigDecimal("20"), "测试地形", List.of("测试风险提示"));
                }
            };
        }
    }
}

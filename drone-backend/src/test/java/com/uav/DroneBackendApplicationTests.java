package com.uav;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.test.context.ActiveProfiles;

/**
 * 修复 QA-B2：原模板测试位于 com.drone 包（主类实际在 com.uav），且依赖真实 MySQL
 * 与全部环境变量，本机/CI 必然失败。现归位于 com.uav，并基于 test profile 的
 * H2 内存库（QA-B5）在 RANDOM_PORT 下加载完整上下文（含 Jakarta WebSocket 容器）。
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class DroneBackendApplicationTests {

    @Test
    void contextLoads() {
    }
}

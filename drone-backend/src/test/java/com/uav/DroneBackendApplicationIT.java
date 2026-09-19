package com.uav;

import com.uav.support.IntegrationTestBase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 应用装配冒烟测试：确认 test profile（H2 内存库）下完整 Spring 上下文可启动。
 *
 * <p>来源与归位（QA-B2）：原模板测试位于 {@code com.drone} 包（主类实际在 {@code com.uav}），
 * 且依赖真实 MySQL 与全部环境变量，本机/CI 必然失败；现归位于 {@code com.uav}，并基于 test profile
 * 的 H2 内存库运行。
 *
 * <p>驱动方式与层次自称（O6/R4）：本类继承 {@link IntegrationTestBase}，走默认 MOCK 环境——进程内加载
 * 上下文，<b>不</b>占用真实端口，也<b>不</b>启动 JSR-356 WebSocket 容器。原实现声明
 * {@code webEnvironment = RANDOM_PORT}，但 {@code ws://}、{@code @LocalServerPort}、{@code java.net.http}、
 * {@code jakarta.websocket} 四项真实端口用途全部为 0 命中，属 R4 的无谓声明。真实 WebSocket 栈的启动与
 * 握手语义由继承 {@code RealProtocolTestBase} 的真实协议端到端测试覆盖，故本类降为 MOCK 不丢覆盖。
 *
 * <p>命名（O5/R9/O4）：原类名以 {@code Tests} 结尾。surefire 的显式 {@code includes} 只覆盖
 * {@code *Test.java}（覆盖了 Maven 默认集合，默认还含 {@code *Tests.java}），而 failsafe 只匹配
 * {@code *IT} / {@code *E2EIT}，导致该类在基线运行中被<b>静默漏执行</b>。现更名为 {@code *IT}
 * 纳入 failsafe 执行层；唯一用例 {@code contextLoads()} 语义保真。
 */
class DroneBackendApplicationIT extends IntegrationTestBase {

    @Test
    @DisplayName("Spring 上下文在 test profile 下加载成功")
    void contextLoads() {
    }
}

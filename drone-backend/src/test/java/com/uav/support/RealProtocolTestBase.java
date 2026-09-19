package com.uav.support;

import com.uav.server.util.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

/**
 * 共享测试基建：真实协议测试基类（R2/R4/R5）。
 *
 * <p>只给「真实端口」测试使用（R4）：WebSocket、真实 HTTP over TCP、需要 {@code @LocalServerPort}
 * 或真库的端到端用例。它启动真实 Servlet 容器，请求由容器线程处理，
 * <b>不含 {@code @Transactional}</b>：事务回滚在容器线程上失效（R5），隔离必须依赖唯一数据或显式清理。
 *
 * <p>R5 例外且强制：断言提交后副作用（{@code afterCommit()} 通知、异步任务、消息投递）的测试
 * 必须用本基类真实提交，不得使用 {@link IntegrationTestBase} 的事务回滚。
 *
 * <p>R8：{@code UserContext} 等 ThreadLocal 由本基类在 {@code @AfterEach} 统一清理。
 */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class RealProtocolTestBase {

    /** 真实监听端口。 */
    @LocalServerPort
    protected int port;

    /** 共享造数工厂（R7）；此处造数真实提交，靠唯一命名隔离。 */
    @Autowired
    protected TestFixtures fixtures;

    private TestAccounts testAccounts;

    /** HTTP 根地址，例如 {@code http://localhost:12345}。 */
    protected String baseUrl() {
        return "http://localhost:" + port;
    }

    /** WebSocket 根地址，例如 {@code ws://localhost:12345}；握手令牌以 {@code ?token=<accessToken>} 传递。 */
    protected String wsBaseUrl() {
        return "ws://localhost:" + port;
    }

    /**
     * 真实身份入口（R3/R5）：走真实注册/登录接口并**真实提交**（本基类无 {@code @Transactional}），
     * 返回的 {@link TestAccounts.Account#token()} 可直接用于 REST Authorization、WebSocket 握手
     * （{@code ws://...?token=}）与 {@code @RequireDrone} 端点；隔离依赖唯一数据而非回滚。
     */
    protected TestAccounts accounts() {
        if (testAccounts == null) {
            testAccounts = TestAccounts.overHttp(port);
        }
        return testAccounts;
    }

    /** R8：统一清理线程上下文，测试类不再各自维护。 */
    @AfterEach
    void clearThreadContexts() {
        UserContext.clear();
        MDC.clear();
    }
}

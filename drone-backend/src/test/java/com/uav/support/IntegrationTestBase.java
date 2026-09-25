package com.uav.support;

import com.uav.server.util.UserContext;
import org.junit.jupiter.api.AfterEach;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.WebApplicationContext;

/**
 * 共享测试基建：业务集成测试基类（R2）。
 *
 * <p>R2 要求集成测试统一 profile 与基类，不得逐类用
 * {@code MockMvcBuilders.webAppContextSetup} 重复搭建：
 * <ul>
 *   <li>{@code @SpringBootTest}（默认 MOCK）——不占用真实端口（R4），MockMvc 在测试线程内同步调用
 *       DispatcherServlet，因而 {@code @Transactional} 能回滚整条请求写入的数据（R5）；</li>
 *   <li>{@code @AutoConfigureMockMvc}——注入上下文真实的 MockMvc（含 JwtInterceptor 与
 *       GlobalExceptionHandler）；</li>
 *   <li>{@code @ActiveProfiles("test")}——H2 内存库测试 profile；</li>
 *   <li>{@code @Transactional}——默认隔离方式为事务回滚。</li>
 * </ul>
 *
 * <p><b>R5 例外</b>：断言提交后副作用（{@code TransactionSynchronization.afterCommit()} 派发的通知、
 * 异步任务、消息投递）的测试不得继承本基类，应继承 {@link RealProtocolTestBase} 或自行去除
 * {@code @Transactional} 并真实提交——回滚事务中 {@code afterCommit()} 永不执行，会静默失去覆盖。
 *
 * <p>R8：{@code UserContext} 等 ThreadLocal 由本基类在 {@code @AfterEach} 统一清理。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
public abstract class IntegrationTestBase {

    /** 上下文真实 MockMvc（含拦截器与异常处理），供 HTTP 断言使用（R6）。 */
    @Autowired
    protected MockMvc mockMvc;

    /** 需要直接构建请求或读取上下文时使用。 */
    @Autowired
    protected WebApplicationContext webApplicationContext;

    /** 共享造数工厂（R7）。 */
    @Autowired
    protected TestFixtures fixtures;

    private TestAccounts testAccounts;

    /** 真实身份入口（R3）：懒加载后复用，每次调用仍分配唯一客户端身份以避开限流。 */
    protected TestAccounts accounts() {
        if (testAccounts == null) {
            testAccounts = TestAccounts.overMockMvc(mockMvc);
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

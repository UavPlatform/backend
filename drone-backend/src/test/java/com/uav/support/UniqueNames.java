package com.uav.support;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 共享测试基建：唯一命名与客户端身份（O2：无 @Test 的工具类，统一放 com.uav.support）。
 *
 * <p>测试 profile 使用 H2 内存库（{@code DB_CLOSE_DELAY=-1}，全 JVM 共享），且 Spring 上下文在
 * 测试运行期间被缓存复用，因此「唯一」的语义是**整个 JVM 测试进程内唯一**，而不是单个测试类内唯一。
 *
 * <p>命名采用「唯一前缀 + 单调计数器」：前缀由每次 JVM 启动生成一次，后缀由原子计数器递增，
 * 既保证可读（前缀能看出用途），也保证跨测试类、跨上下文不冲突。**不使用
 * {@code System.nanoTime()} 作为隔离手段**——它既不可读，也不能表达「同一进程内递增」的意图。
 */
public final class UniqueNames {

    /** 每次 JVM 启动生成一次的唯一前缀（32 位随机十六进制）。 */
    private static final String RUN_TAG = UUID.randomUUID().toString().replace("-", "").substring(0, 8);

    /** 进程内单调计数器，保证同一前缀下不会重复。 */
    private static final AtomicLong SEQ = new AtomicLong();

    private UniqueNames() {
    }

    /** 生成可读的唯一标识：{@code <prefix>-<runTag>-<seq>}。 */
    public static String unique(String prefix) {
        return prefix + "-" + RUN_TAG + "-" + SEQ.incrementAndGet();
    }

    /** 默认用户唯一用户名。 */
    public static String userName() {
        return userName("u");
    }

    /** 带用途前缀的唯一用户名，例如 {@code rider-xxxx-7}。 */
    public static String userName(String prefix) {
        return unique(prefix);
    }

    /** 唯一无人机 DJI ID（{@code rider_uav.dji_id} 全库唯一，绑定前会查重）。 */
    public static String djiId() {
        return unique("dji");
    }

    /**
     * 每个调用一个唯一的客户端身份，写进 {@code X-Forwarded-For}。
     *
     * <p>{@code RateLimiterAspect} 的 key 为「方法签名 + UserContext.username，username 为空时退化为
     * X-Forwarded-For / remoteAddr」。MockMvc 与真实 TCP 下 remoteAddr 恒为回环地址，若共用身份，
     * 第 4 次注册（3 次/60s）或第 6 次登录（5 次/60s）起必然 429。测试必须为每次调用分配独立身份，
     * 而不是放宽或关闭生产限流。
     *
     * <p>使用 198.18.0.0/15（RFC 2544 基准测试网段）内的地址，避免与真实网段混淆；
     * 计数器保证同一 JVM 内不重复。
     */
    public static String clientIp() {
        long n = SEQ.incrementAndGet();
        return "198.18." + ((n >> 8) & 0xFF) + "." + (n & 0xFF);
    }
}

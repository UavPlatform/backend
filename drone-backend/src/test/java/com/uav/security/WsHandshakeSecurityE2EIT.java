package com.uav.security;

import com.uav.support.RealProtocolTestBase;
import com.uav.support.TestAccounts;
import com.uav.support.UniqueNames;
import jakarta.websocket.ClientEndpointConfig;
import jakarta.websocket.CloseReason;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.DeploymentException;
import jakarta.websocket.Endpoint;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.Session;
import jakarta.websocket.WebSocketContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * WS 端点未授权握手防护测试（P0-1）：三个 WS 端点的未授权/身份不符握手必须被拒绝。
 * <ul>
 *   <li>/ws/{sid}（Jakarta chat）：无 token / token 与 sid 不符 → 拒绝；匹配 → 连通</li>
 *   <li>/ws/web：无 token → 拒绝；有 token → 连通</li>
 *   <li>/ws/drone：无 token / 非飞手 / 设备未绑定 → 拒绝；绑定飞手 → 连通</li>
 * </ul>
 *
 * <p>层次与驱动（R4/R9/O4/O6）：真实 WebSocket 协议测试——继承 {@link RealProtocolTestBase}
 * （{@code RANDOM_PORT} + {@code @LocalServerPort}）并用 Jakarta WebSocket 客户端真实建连，
 * 因此命名 {@code *E2EIT}，且<b>不含</b> {@code @Transactional}（R5：容器线程上事务回滚失效，
 * 隔离依赖唯一数据与显式清理）。
 *
 * <p>身份来源（R3）：全部走 {@link TestAccounts} 的真实注册/登录接口，不再构造自签 token。
 * 「身份不符」用两个真实用户互串（一方 token + 另一方 sid），「非飞手」用真实普通用户，
 * 「已绑定飞手」用 {@code /rider/register} 的真实绑定链路（服务端据此写入 RiderUav），
 * 因此本类不手工插入任何造数行（R7），隔离靠唯一用户名/DJI ID（R5）。
 */
class WsHandshakeSecurityE2EIT extends RealProtocolTestBase {

    private final List<Session> openSessions = new ArrayList<>();

    /** 未登录场景使用的数字 sid：无 token 时在鉴权阶段即被拒，与该身份是否存在无关。 */
    private static final long ANONYMOUS_SID = 987_654_321L;

    /** R5：非事务真实协议测试必须显式清理已建立的连接。 */
    @AfterEach
    void closeOpenSessions() {
        for (Session session : openSessions) {
            try {
                session.close();
            } catch (Exception ignored) {
                // 关闭失败不影响用例结论
            }
        }
        openSessions.clear();
    }

    // ---------- /ws/{sid}（聊天） ----------

    @Test
    @DisplayName("聊天 WS：无 token 握手被拒")
    void chatWsWithoutTokenRejected() {
        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        URI uri = URI.create(wsBaseUrl() + "/ws/" + ANONYMOUS_SID);
        assertThrows(DeploymentException.class, () -> container.connectToServer(
                new OpenLatchEndpoint(), ClientEndpointConfig.Builder.create().build(), uri));
    }

    @Test
    @DisplayName("聊天 WS：token 用户与 sid 不符握手被拒（不能冒充他人）")
    void chatWsIdentityMismatchRejected() throws Exception {
        TestAccounts.Account attacker = accounts().registerUser();
        TestAccounts.Account owner = accounts().registerUser();

        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        URI uri = appendToken(URI.create(wsBaseUrl() + "/ws/" + owner.id()), attacker.token());
        assertThrows(DeploymentException.class, () -> container.connectToServer(
                new OpenLatchEndpoint(), ClientEndpointConfig.Builder.create().build(), uri));
    }

    @Test
    @DisplayName("聊天 WS：token 用户与 sid 一致握手成功")
    void chatWsMatchingIdentityAccepted() throws Exception {
        TestAccounts.Account user = accounts().registerUser();

        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        URI uri = appendToken(URI.create(wsBaseUrl() + "/ws/" + user.id()), user.token());
        OpenLatchEndpoint endpoint = new OpenLatchEndpoint();
        Session session = container.connectToServer(endpoint, ClientEndpointConfig.Builder.create().build(), uri);
        openSessions.add(session);

        assertThat(endpoint.opened.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(session.isOpen()).isTrue();
    }

    // ---------- /ws/web（观看/大屏通道） ----------

    @Test
    @DisplayName("Web 观看 WS：无 token 握手被拒")
    void webWsWithoutTokenRejected() {
        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        URI uri = URI.create(wsBaseUrl() + "/ws/web?deviceId=" + UniqueNames.unique("none"));
        assertThrows(DeploymentException.class, () -> container.connectToServer(
                new OpenLatchEndpoint(), ClientEndpointConfig.Builder.create().build(), uri));
    }

    @Test
    @DisplayName("Web 观看 WS：登录用户握手成功")
    void webWsWithTokenAccepted() throws Exception {
        TestAccounts.Account user = accounts().registerUser();

        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        URI uri = appendToken(
                URI.create(wsBaseUrl() + "/ws/web?deviceId=" + UniqueNames.unique("none")), user.token());
        OpenLatchEndpoint endpoint = new OpenLatchEndpoint();
        Session session = container.connectToServer(endpoint, ClientEndpointConfig.Builder.create().build(), uri);
        openSessions.add(session);

        assertThat(endpoint.opened.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(session.isOpen()).isTrue();
    }

    // ---------- /ws/drone（设备通道） ----------

    @Test
    @DisplayName("设备 WS：无 token 握手被拒")
    void droneWsWithoutTokenRejected() {
        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        URI uri = URI.create(wsBaseUrl() + "/ws/drone?deviceId=" + UniqueNames.djiId());
        assertThrows(DeploymentException.class, () -> container.connectToServer(
                new OpenLatchEndpoint(), ClientEndpointConfig.Builder.create().build(), uri));
    }

    @Test
    @DisplayName("设备 WS：非飞手身份（普通用户）握手被拒")
    void droneWsNonRiderRejected() throws Exception {
        TestAccounts.Account normal = accounts().registerUser();

        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        URI uri = appendToken(
                URI.create(wsBaseUrl() + "/ws/drone?deviceId=" + UniqueNames.djiId()), normal.token());
        assertThrows(DeploymentException.class, () -> container.connectToServer(
                new OpenLatchEndpoint(), ClientEndpointConfig.Builder.create().build(), uri));
    }

    @Test
    @DisplayName("设备 WS：飞手连接未绑定设备握手被拒")
    void droneWsUnboundRiderRejected() throws Exception {
        TestAccounts.Account rider = accounts().registerRider(UniqueNames.userName("rider"), null);

        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        URI uri = appendToken(
                URI.create(wsBaseUrl() + "/ws/drone?deviceId=" + UniqueNames.djiId()), rider.token());
        assertThrows(DeploymentException.class, () -> container.connectToServer(
                new OpenLatchEndpoint(), ClientEndpointConfig.Builder.create().build(), uri));
    }

    @Test
    @DisplayName("设备 WS：绑定设备的飞手握手成功")
    void droneWsBoundRiderAccepted() throws Exception {
        String deviceId = UniqueNames.djiId();
        TestAccounts.Account rider = accounts().registerRider(UniqueNames.userName("rider"), deviceId);

        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        URI uri = appendToken(URI.create(wsBaseUrl() + "/ws/drone?deviceId=" + deviceId), rider.token());
        OpenLatchEndpoint endpoint = new OpenLatchEndpoint();
        Session session = container.connectToServer(endpoint, ClientEndpointConfig.Builder.create().build(), uri);
        openSessions.add(session);

        assertThat(endpoint.opened.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(session.isOpen()).isTrue();
    }

    // ---------- helpers ----------

    /** R3：WebSocket 握手令牌以 {@code ?token=<accessToken>} 传递（服务端从查询参数取 JWT）。 */
    private static URI appendToken(URI uri, String token) {
        String base = uri.toString();
        String sep = base.contains("?") ? "&" : "?";
        return URI.create(base + sep + "token=" + token);
    }

    /** 连接成功即放行的测试端点。 */
    static class OpenLatchEndpoint extends Endpoint {
        final CountDownLatch opened = new CountDownLatch(1);
        final AtomicReference<Throwable> error = new AtomicReference<>();

        @Override
        public void onOpen(Session session, EndpointConfig config) {
            opened.countDown();
        }

        @Override
        public void onClose(Session session, CloseReason closeReason) {
            // no-op
        }

        @Override
        public void onError(Session session, Throwable thr) {
            error.set(thr);
        }
    }
}

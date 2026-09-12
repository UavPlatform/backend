package com.uav.security;

import com.uav.server.util.JwtUtil;
import com.uav.user.mapper.RiderUavRepository;
import com.uav.user.pojo.entity.RiderUav;
import jakarta.websocket.ClientEndpointConfig;
import jakarta.websocket.CloseReason;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.DeploymentException;
import jakarta.websocket.Endpoint;
import jakarta.websocket.EndpointConfig;
import jakarta.websocket.Session;
import jakarta.websocket.WebSocketContainer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import java.net.URI;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * P0-1 防护测试：三个 WS 端点的未授权握手必须被拒绝。
 * <ul>
 *   <li>/ws/{sid}（Jakarta chat）：无 token / token 与 sid 不符 → 拒绝；匹配 → 连通</li>
 *   <li>/ws/web：无 token → 403 拒绝；有 token → 连通</li>
 *   <li>/ws/drone：无 token / 非飞手 / 设备未绑定 → 拒绝；绑定飞手 → 连通</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class WsHandshakeSecurityTest {

    @LocalServerPort
    int port;

    @Autowired
    JwtUtil jwtUtil;

    @Autowired
    RiderUavRepository riderUavRepository;

    private final List<Session> openSessions = new java.util.ArrayList<>();

    private long rid;

    @BeforeEach
    void setUp() {
        rid = System.nanoTime();
    }

    @AfterEach
    void tearDown() {
        for (Session session : openSessions) {
            try { session.close(); } catch (Exception ignored) { }
        }
        openSessions.clear();
    }

    // ---------- /ws/{sid}（聊天） ----------

    @Test
    @DisplayName("聊天 WS：无 token 握手被拒")
    void chatWsWithoutTokenRejected() {
        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        URI uri = URI.create("ws://localhost:" + port + "/ws/" + (900_000 + rid % 100_000));
        assertThrows(DeploymentException.class, () -> container.connectToServer(
                new OpenLatchEndpoint(), ClientEndpointConfig.Builder.create().build(), uri));
    }

    @Test
    @DisplayName("聊天 WS：token 用户与 sid 不符握手被拒（不能冒充他人）")
    void chatWsIdentityMismatchRejected() throws Exception {
        long ownerId = 910_000 + rid % 100_000;
        long attackerId = 911_000 + rid % 100_000;
        String attackerToken = jwtUtil.generateToken(attackerId, "attacker" + rid, 0);

        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        URI uri = URI.create("ws://localhost:" + port + "/ws/" + ownerId);
        assertThrows(DeploymentException.class, () -> container.connectToServer(
                new OpenLatchEndpoint(), ClientEndpointConfig.Builder.create().build(),
                appendToken(uri, attackerToken)));
    }

    @Test
    @DisplayName("聊天 WS：token 用户与 sid 一致握手成功")
    void chatWsMatchingIdentityAccepted() throws Exception {
        long userId = 912_000 + rid % 100_000;
        String token = jwtUtil.generateToken(userId, "user" + rid, 0);

        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        URI uri = appendToken(URI.create("ws://localhost:" + port + "/ws/" + userId), token);
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
        URI uri = URI.create("ws://localhost:" + port + "/ws/web?deviceId=none-" + rid);
        assertThrows(DeploymentException.class, () -> container.connectToServer(
                new OpenLatchEndpoint(), ClientEndpointConfig.Builder.create().build(), uri));
    }

    @Test
    @DisplayName("Web 观看 WS：登录用户握手成功")
    void webWsWithTokenAccepted() throws Exception {
        long userId = 913_000 + rid % 100_000;
        String token = jwtUtil.generateToken(userId, "webuser" + rid, 0);

        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        URI uri = appendToken(URI.create("ws://localhost:" + port + "/ws/web?deviceId=none-" + rid), token);
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
        URI uri = URI.create("ws://localhost:" + port + "/ws/drone?deviceId=drone-" + rid);
        assertThrows(DeploymentException.class, () -> container.connectToServer(
                new OpenLatchEndpoint(), ClientEndpointConfig.Builder.create().build(), uri));
    }

    @Test
    @DisplayName("设备 WS：非飞手身份（普通用户）握手被拒")
    void droneWsNonRiderRejected() throws Exception {
        long userId = 914_000 + rid % 100_000;
        String token = jwtUtil.generateToken(userId, "normal" + rid, 0);

        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        URI uri = appendToken(URI.create("ws://localhost:" + port + "/ws/drone?deviceId=drone-" + rid), token);
        assertThrows(DeploymentException.class, () -> container.connectToServer(
                new OpenLatchEndpoint(), ClientEndpointConfig.Builder.create().build(), uri));
    }

    @Test
    @DisplayName("设备 WS：飞手连接未绑定设备握手被拒")
    void droneWsUnboundRiderRejected() throws Exception {
        long riderId = 915_000 + rid % 100_000;
        String token = jwtUtil.generateToken(riderId, "rider" + rid, 1);

        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        URI uri = appendToken(URI.create("ws://localhost:" + port + "/ws/drone?deviceId=other-drone-" + rid), token);
        assertThrows(DeploymentException.class, () -> container.connectToServer(
                new OpenLatchEndpoint(), ClientEndpointConfig.Builder.create().build(), uri));
    }

    @Test
    @DisplayName("设备 WS：绑定设备的飞手握手成功")
    void droneWsBoundRiderAccepted() throws Exception {
        long riderId = 916_000 + rid % 100_000;
        String deviceId = "my-drone-" + rid;
        RiderUav binding = new RiderUav();
        binding.setUserId(riderId);
        binding.setDjiId(deviceId);
        riderUavRepository.save(binding);

        String token = jwtUtil.generateToken(riderId, "rider" + rid, 1);

        WebSocketContainer container = ContainerProvider.getWebSocketContainer();
        URI uri = appendToken(URI.create("ws://localhost:" + port + "/ws/drone?deviceId=" + deviceId), token);
        OpenLatchEndpoint endpoint = new OpenLatchEndpoint();
        Session session = container.connectToServer(endpoint, ClientEndpointConfig.Builder.create().build(), uri);
        openSessions.add(session);

        assertThat(endpoint.opened.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(session.isOpen()).isTrue();
    }

    // ---------- helpers ----------

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

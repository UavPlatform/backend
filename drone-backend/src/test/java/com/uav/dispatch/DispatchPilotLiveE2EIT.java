package com.uav.dispatch;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.live.service.AppWebSocketService;
import com.uav.support.RealProtocolTestBase;
import com.uav.support.TestAccounts.Account;
import com.uav.support.UniqueNames;
import com.uav.uav.mapper.UavRepository;
import com.uav.uav.pojo.entity.Uav;
import jakarta.websocket.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-0007 (2026-10-22): the drone belongs to the dispatch task, not the pilot. A pilot without any manually bound
 * drone can open the device channel and start live for the device that is the active flight connection of their
 * own dispatch task; other pilots, and the same pilot after the connection ended, are still rejected.
 */
@TestPropertySource(properties = {"wechat.pay.mock-enabled=true", "dispatch.demo.enabled=true"})
class DispatchPilotLiveE2EIT extends RealProtocolTestBase {
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    @Autowired private UavRepository uavs;
    @Autowired private AppWebSocketService appWebSocketService;

    @Test
    @DisplayName("未手动绑机的飞手：本人派单当前连机的无人机可连设备通道并开播；他人和连接结束后被拒")
    void unboundPilotStartsLiveForOwnExecutingDrone() throws Exception {
        Account owner = accounts().registerUser();
        Account pilot = accounts().registerRider(UniqueNames.userName("live"), null);
        Account other = accounts().registerRider(UniqueNames.userName("live2"), null);
        String serial = UniqueNames.djiId().toUpperCase(Locale.ROOT);
        registerUav(serial);
        String id = prepare(owner, pilot);

        // Before any flight connection the unbound pilot has no access to the device.
        assertThat(post("/live/rider/req?deviceId=" + serial, pilot, null).statusCode()).isEqualTo(403);

        String cid = data(post("/dispatch/pilot/orders/" + id + "/flight-connections", pilot, Map.of(
                "assignmentVersion", 1, "requestKey", UUID.randomUUID().toString(), "serialNumber", serial,
                "modelCode", "FC30", "modelName", "DJI FlyCart 30"))).path("connectionId").asText();

        // Another pilot can neither open the device channel nor start live for it.
        assertThat(openDevice(serial, other)).isNull();
        assertThat(post("/live/rider/req?deviceId=" + serial, other, null).statusCode()).isEqualTo(403);

        appWebSocketService.requestConnection(serial);
        AckEndpoint device = openDevice(serial, pilot);
        assertThat(device).isNotNull();
        long deadline = System.currentTimeMillis() + 5000;
        while (!appWebSocketService.isConnected(serial) && System.currentTimeMillis() < deadline) Thread.sleep(50);

        var started = post("/live/rider/req?deviceId=" + serial, pilot, null);
        assertThat(started.statusCode()).isEqualTo(200);
        assertThat(started.body()).contains("设备已确认启动图传").doesNotContain("设备未绑定到当前飞手");
        assertThat(device.startLive.await(5, TimeUnit.SECONDS)).isTrue();
        post("/live/close?deviceId=" + serial, owner, null);

        // Once the connection ends the device is no longer the pilot's.
        assertThat(post("/dispatch/pilot/orders/" + id + "/flight-connections/" + cid + "/close", pilot, Map.of()).statusCode()).isEqualTo(200);
        assertThat(post("/live/rider/req?deviceId=" + serial, pilot, null).statusCode()).isEqualTo(403);
        device.session.close();
    }

    private String prepare(Account owner, Account pilot) throws Exception {
        var waybill = DispatchTestData.waybill(DispatchTestData.fields());
        var assessment = data(post("/dispatch/assessments", owner, waybill));
        String id = data(post("/dispatch/orders", owner, Map.of("assessmentId", assessment.path("assessmentId").asText(),
                "requestKey", UUID.randomUUID().toString(), "declarationAccepted", true, "waybill", waybill))).path("orderId").asText();
        data(post("/dispatch/orders/" + id + "/simulate-payment", owner, Map.of()));
        JsonNode card = null;
        var hall = http.send(HttpRequest.newBuilder(URI.create(baseUrl() + "/dispatch/pilot/hall"))
                .header("Authorization", pilot.authorization()).GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        for (var item : json.readTree(hall.body()).path("data").path("items")) if (id.equals(item.path("orderId").asText())) card = item;
        assertThat(card).isNotNull();
        data(post("/dispatch/pilot/orders/" + id + "/claim", pilot, Map.of("offerRevision", card.path("offerRevision").asLong())));
        data(post("/dispatch/pilot/orders/" + id + "/arrive", pilot, Map.of("assignmentVersion", 1)));
        return id;
    }

    private JsonNode data(HttpResponse<String> r) throws Exception {
        assertThat(r.statusCode()).as(r.body()).isEqualTo(200);
        return json.readTree(r.body()).path("data");
    }

    private HttpResponse<String> post(String path, Account actor, Object body) throws Exception {
        var b = HttpRequest.newBuilder(URI.create(baseUrl() + path)).timeout(Duration.ofSeconds(30))
                .header("Authorization", actor.authorization());
        if (body == null) b.POST(HttpRequest.BodyPublishers.noBody());
        else b.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private void registerUav(String deviceId) {
        Uav uav = uavs.findPrimaryByDjiId(deviceId).orElseGet(Uav::new);
        uav.setUavName("dl-" + deviceId); uav.setDjiId(deviceId); uav.setOnlineStatus('1');
        uav.setControllerModel("cm"); uav.setIsAvailable('1');
        uavs.save(uav);
    }

    /** @return the open endpoint, or null when the handshake is rejected. */
    private AckEndpoint openDevice(String deviceId, Account pilot) throws Exception {
        var endpoint = new AckEndpoint();
        try {
            endpoint.session = ContainerProvider.getWebSocketContainer().connectToServer(endpoint,
                    ClientEndpointConfig.Builder.create().build(),
                    URI.create(wsBaseUrl() + "/ws/drone?deviceId=" + deviceId + "&token=" + pilot.token()));
        } catch (DeploymentException | java.io.IOException rejected) {
            return null;
        }
        return endpoint.opened.await(10, TimeUnit.SECONDS) ? endpoint : null;
    }

    /** Device side: acknowledges START_LIVE / STOP_LIVE synchronously. */
    static class AckEndpoint extends Endpoint {
        final CountDownLatch opened = new CountDownLatch(1);
        final CountDownLatch startLive = new CountDownLatch(1);
        volatile Session session;

        @Override
        public void onOpen(Session session, EndpointConfig config) {
            session.addMessageHandler(new MessageHandler.Whole<String>() {
                @Override
                public void onMessage(String message) {
                    if (!message.contains("START_LIVE") && !message.contains("STOP_LIVE")) return;
                    if (message.contains("START_LIVE")) startLive.countDown();
                    JSONObject ack = new JSONObject();
                    ack.put("type", "response"); ack.put("replyTo", JSON.parseObject(message).getString("id")); ack.put("success", true);
                    try { session.getBasicRemote().sendText(ack.toJSONString()); } catch (java.io.IOException ignored) { }
                }
            });
            opened.countDown();
        }
    }
}

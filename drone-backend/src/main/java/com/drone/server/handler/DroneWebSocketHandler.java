package com.drone.server.handler;

import com.drone.pojo.dto.UavStatusDto;
import com.drone.service.AppWebSocketService;
import com.drone.service.UavStatusService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;

@Component
@Slf4j
public class DroneWebSocketHandler extends TextWebSocketHandler {

    @Autowired
    private AppWebSocketService appWebSocketService;

    @Autowired
    private UavStatusService uavStatusService;

    // 存储设备ID与WebSocket会话的映射
    private final Map<String, SessionInfo> sessions = new ConcurrentHashMap<>();

    // 存储所有已连接的无人机会话
    private final Map<String, WebSocketSession> liveWebSessions = new ConcurrentHashMap<>();

    // 定时任务线程池，用于心跳检测
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    // 连接超时时间（毫秒）
    private static final long TIMEOUT = 60000; // 60秒

    // 心跳间隔（毫秒）
    private static final long HEARTBEAT_INTERVAL = 30000; // 30秒

    public DroneWebSocketHandler() {
        //启动心跳检测任务
        scheduler.scheduleAtFixedRate(this::checkSessions, HEARTBEAT_INTERVAL, HEARTBEAT_INTERVAL, TimeUnit.MILLISECONDS);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        //从URL参数中获取设备ID
        String query = session.getUri().getQuery();
        if (query != null && query.contains("deviceId")) {
            String deviceId = query.split("=")[1];

            //是否已申请连接
            if (appWebSocketService.isPending(deviceId)) {
                sessions.put(deviceId, new SessionInfo(session, System.currentTimeMillis()));
                appWebSocketService.markAsConnected(deviceId);
                log.info("设备 {} 已连接", deviceId);

                //发送连接成功消息
                Map<String, Object> message = new ConcurrentHashMap<>();
                message.put("type", "CONNECT_SUCCESS");
                message.put("message", "连接成功");
                session.sendMessage(new TextMessage(com.alibaba.fastjson.JSON.toJSONString(message)));
            } else {
                //未申请连接，拒绝连接
                session.close(CloseStatus.POLICY_VIOLATION);
                log.info("设备 {} 未申请连接，拒绝连接", deviceId);
            }
        } else {
            //无设备ID，拒绝连接
            session.close(CloseStatus.BAD_DATA);
            log.info("无设备ID，拒绝连接");
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        // 处理App端发送的消息
        String payload = message.getPayload();
        try {
            com.alibaba.fastjson.JSONObject json = com.alibaba.fastjson.JSON.parseObject(payload);
            String type = json.getString("type");
            
            if ("ping".equals(type)) {
                // 处理心跳
                session.sendMessage(new TextMessage("pong"));
                updateActiveTime(session);
            } else if ("UAV_STATUS".equals(type)) {
                // 处理无人机状态信息
                handleUavStatus(json, session);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // 处理无人机状态信息
    private void handleUavStatus(com.alibaba.fastjson.JSONObject json, WebSocketSession session) {
        try {
            // 获取设备ID
            String deviceId = getDeviceIdFromSession(session);
            if (deviceId == null) return;
            
            // 获取无人机状态数据
            com.alibaba.fastjson.JSONObject data = json.getJSONObject("data");
            UavStatusDto status = data.toJavaObject(UavStatusDto.class);
            
            // 存储无人机状态
            uavStatusService.updateUavStatus(status);
            
            // 发送状态信息到指定开播的Web端
            sendToLiveWebClient(deviceId, status);
            
            log.info("收到无人机 {} 的状态信息，操作：{}", status.getUavName(), status.getOperation());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    // 完善sendToLiveWebClient方法
    private void sendToLiveWebClient(String deviceId, UavStatusDto status) {
        // 获取对应的Web端会话
        WebSocketSession webSession = liveWebSessions.get(deviceId);
        if (webSession != null && webSession.isOpen()) {
            Map<String, Object> message = new ConcurrentHashMap<>();
            message.put("type", "UAV_STATUS_UPDATE");
            message.put("data", status);
            String jsonMessage = com.alibaba.fastjson.JSON.toJSONString(message);
            try {
                webSession.sendMessage(new TextMessage(jsonMessage));
            } catch (IOException e) {
                e.printStackTrace();
                liveWebSessions.remove(deviceId);
            }
        }
    }
    //注册开播的Web端连接
    public void registerLiveWebSession(String deviceId, WebSocketSession session) {
// 注册为开播的Web端连接
        liveWebSessions.put(deviceId, session);
        log.info("Web端已注册为设备 {} 的直播客户端", deviceId);
    }

    //移除开播的Web端连接
    public void removeLiveWebSession(String deviceId) {
// 移除开播的Web端连接
        liveWebSessions.remove(deviceId);
        log.info("Web端已取消注册为设备 {} 的直播客户端", deviceId);
    }
    

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        // 从sessions中移除对应的设备
        String deviceId = getDeviceIdFromSession(session);
        if (deviceId != null) {
            sessions.remove(deviceId);
            appWebSocketService.markAsDisconnected(deviceId);
            log.info("设备 {} 已断开连接", deviceId);
        }
    }

    // 向指定设备发送消息
    public boolean sendMessage(String deviceId, String message) {
        SessionInfo info = sessions.get(deviceId);
        if (info != null && info.session.isOpen()) {
            try {
                info.session.sendMessage(new TextMessage(message));
                info.lastActiveTime = System.currentTimeMillis();
                return true;
            } catch (IOException e) {
                e.printStackTrace();
                // 发送失败，移除连接
                sessions.remove(deviceId);
                appWebSocketService.markAsDisconnected(deviceId);
                return false;
            }
        }
        return false;
    }

    // 心跳检测
    private void checkSessions() {
        long currentTime = System.currentTimeMillis();
        sessions.entrySet().removeIf(entry -> {
            SessionInfo info = entry.getValue();
            if (currentTime - info.lastActiveTime > TIMEOUT) {
                try {
                    info.session.close();
                    log.info("设备 {} 连接超时，已关闭", entry.getKey());
                } catch (Exception e) {
                    e.printStackTrace();
                }
                appWebSocketService.markAsDisconnected(entry.getKey());
                return true;
            }
            return false;
        });
    }

    // 更新活跃时间
    private void updateActiveTime(WebSocketSession session) {
        String deviceId = getDeviceIdFromSession(session);
        if (deviceId != null) {
            SessionInfo info = sessions.get(deviceId);
            if (info != null) {
                info.lastActiveTime = System.currentTimeMillis();
            }
        }
    }

    // 从会话中获取设备ID
    private String getDeviceIdFromSession(WebSocketSession session) {
        for (Map.Entry<String, SessionInfo> entry : sessions.entrySet()) {
            if (entry.getValue().session.equals(session)) {
                return entry.getKey();
            }
        }
        return null;
    }

    // 会话信息类
    private static class SessionInfo {
        WebSocketSession session;
        long lastActiveTime;

        SessionInfo(WebSocketSession session, long lastActiveTime) {
            this.session = session;
            this.lastActiveTime = lastActiveTime;
        }
    }
}
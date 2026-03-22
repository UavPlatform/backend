package com.drone.service.impl;

import com.drone.service.AppWebSocketService;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AppWebSocketServiceImpl implements AppWebSocketService {
    //存储待连接的设备ID
    private final Set<String> pendingDevices = ConcurrentHashMap.newKeySet();

    //存储已建立的WebSocket连接状态
    private final Map<String, Boolean> connectedDevices = new ConcurrentHashMap<>();

    /**
     * 申请WebSocket连接
     * @param deviceId 设备ID
     * @return 是否申请成功
     */
    public boolean requestConnection(String deviceId) {
        if (connectedDevices.containsKey(deviceId) && connectedDevices.get(deviceId)) {
            return false; // 设备已连接
        }
        pendingDevices.add(deviceId);
        return true;
    }

    /**
     * 验证设备是否已申请连接
     * @param deviceId 设备ID
     * @return 是否已申请
     */
    public boolean isPending(String deviceId) {
        return pendingDevices.contains(deviceId);
    }

    /**
     * 标记设备为已连接
     * @param deviceId 设备ID
     */
    public void markAsConnected(String deviceId) {
        pendingDevices.remove(deviceId);
        connectedDevices.put(deviceId, true);
    }

    /**
     * 标记设备为已断开
     * @param deviceId 设备ID
     */
    public void markAsDisconnected(String deviceId) {
        connectedDevices.put(deviceId, false);
    }

    /**
     * 检查设备是否已连接
     * @param deviceId 设备ID
     * @return 是否已连接
     */
    public boolean isConnected(String deviceId) {
        return connectedDevices.containsKey(deviceId) && connectedDevices.get(deviceId);
    }

    /**
     * 获取所有已连接的设备ID
     * @return 已连接设备ID列表
     */
    public Set<String> getConnectedDevices() {
        Set<String> connected = ConcurrentHashMap.newKeySet();
        for (Map.Entry<String, Boolean> entry : connectedDevices.entrySet()) {
            if (entry.getValue()) {
                connected.add(entry.getKey());
            }
        }
        return connected;
    }
}
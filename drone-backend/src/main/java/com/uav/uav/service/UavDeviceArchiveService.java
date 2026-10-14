package com.uav.uav.service;

import com.uav.uav.mapper.UavRepository;
import com.uav.uav.pojo.entity.Uav;
import org.springframework.stereotype.Service;

/**
 * 设备档案（{@code uav} 表）与飞手绑定（{@code rider_uav}）的衔接：绑机路径只写归属时，
 * 监管统计与启停仍读档案表，此处按 {@code dji_id} 幂等补档。
 */
@Service
public class UavDeviceArchiveService {

    private static final char OFFLINE = '0';
    private static final char AVAILABLE = '1';

    private final UavRepository uavRepository;

    public UavDeviceArchiveService(UavRepository uavRepository) {
        this.uavRepository = uavRepository;
    }

    /**
     * 绑定成功后确保存在设备档案；已有行则不改动名称、在线、可用等运行时字段。
     */
    public void ensureArchiveForBinding(String djiId) {
        if (djiId == null || djiId.isBlank()) {
            return;
        }
        if (uavRepository.findByDjiId(djiId).isPresent()) {
            return;
        }
        Uav uav = new Uav();
        uav.setDjiId(djiId);
        uav.setUavName(djiId);
        uav.setOnlineStatus(OFFLINE);
        uav.setIsAvailable(AVAILABLE);
        uavRepository.save(uav);
    }
}

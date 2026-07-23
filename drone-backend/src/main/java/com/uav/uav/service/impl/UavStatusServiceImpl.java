package com.uav.uav.service.impl;

import com.uav.uav.mapper.GpsRecordRepository;
import com.uav.uav.pojo.dto.UavStatusDto;
import com.uav.uav.pojo.entity.UavGpsRecord;
import com.uav.uav.pojo.vo.GpsPointVO;
import com.uav.uav.service.UavStatusService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

@Service
@Slf4j
public class UavStatusServiceImpl implements UavStatusService {

    @Autowired
    private GpsRecordRepository gpsRecordRepository;

    /** 无人机状态：按 uavId */
    private final Map<Long, UavStatusDto> uavStatusMap = new ConcurrentHashMap<>();
    /** 无人机状态：按 deviceId */
    private final Map<String, UavStatusDto> deviceStatusMap = new ConcurrentHashMap<>();
    /** 设备 → 订单绑定（用于 GPS 记录自动关联订单号） */
    private final Map<String, String> deviceOrderMap = new ConcurrentHashMap<>();

    /** GPS 记录缓冲队列，异步批量写库 */
    private final LinkedBlockingQueue<UavGpsRecord> gpsBuffer = new LinkedBlockingQueue<>(10000);

    private static final int BATCH_SIZE = 200;

    @Override
    public void updateUavStatus(UavStatusDto status) {
        if (status == null) {
            return;
        }
        if (status.getUavId() != null) {
            uavStatusMap.put(status.getUavId(), status);
        }
        if (status.getDeviceId() != null && !status.getDeviceId().isBlank()) {
            deviceStatusMap.put(status.getDeviceId(), status);
        }
        // 异步写库：放入缓冲队列
        enqueueGpsRecord(status);
    }

    @Override
    public void updateUavStatus(String deviceId, UavStatusDto status) {
        if (status == null) {
            return;
        }
        status.setDeviceId(deviceId);
        if (status.getReceivedAt() <= 0) {
            status.setReceivedAt(System.currentTimeMillis());
        }
        updateUavStatus(status);
    }

    @Override
    public UavStatusDto getUavStatus(Long uavId) {
        return uavStatusMap.get(uavId);
    }

    @Override
    public UavStatusDto getUavStatus(String deviceId) {
        return deviceStatusMap.get(deviceId);
    }

    @Override
    public Map<Long, UavStatusDto> getAllUavStatus() {
        return uavStatusMap;
    }

    @Override
    public Map<String, UavStatusDto> getAllUavStatusByDeviceId() {
        return deviceStatusMap;
    }

    // ---- 设备-订单绑定 ----

    @Override
    public void bindDeviceToOrder(String deviceId, String orderNum) {
        if (deviceId == null || orderNum == null) return;
        deviceOrderMap.put(deviceId, orderNum);
        log.info("设备 {} 已绑定订单 {}", deviceId, orderNum);
    }

    @Override
    public void clearDeviceOrderBinding(String deviceId) {
        if (deviceId != null) {
            deviceOrderMap.remove(deviceId);
            log.info("设备 {} 已解除订单绑定", deviceId);
        }
    }

    @Override
    public String getOrderNumByDevice(String deviceId) {
        return deviceId != null ? deviceOrderMap.get(deviceId) : null;
    }

    // ---- GPS 查询 ----

    @Override
    public List<UavGpsRecord> getTrajectoryByOrderNum(String orderNum) {
        return gpsRecordRepository.findByOrderNumOrderByTimestampAsc(orderNum);
    }

    @Override
    public GpsPointVO getLatestPosition(Long uavId, String deviceId) {
        // 优先从内存查（实时数据，毫秒级延迟）
        if (uavId != null) {
            UavStatusDto status = uavStatusMap.get(uavId);
            if (status != null) return GpsPointVO.from(status);
        }
        if (deviceId != null) {
            UavStatusDto status = deviceStatusMap.get(deviceId);
            if (status != null) return GpsPointVO.from(status);
            // 降级：DB 查最近一条
            UavGpsRecord record = gpsRecordRepository.findFirstByDeviceIdOrderByTimestampDesc(deviceId);
            if (record != null) return GpsPointVO.from(record);
        }
        if (uavId != null) {
            // 降级：DB 查最近 2 分钟
            List<UavGpsRecord> records = gpsRecordRepository
                    .findByUavIdAndTimestampAfterOrderByTimestampAsc(uavId,
                            System.currentTimeMillis() - 120_000);
            if (!records.isEmpty()) {
                return GpsPointVO.from(records.get(records.size() - 1));
            }
        }
        return null;
    }

    // ---- 异步批量写库 ----

    private void enqueueGpsRecord(UavStatusDto status) {
        UavGpsRecord record = new UavGpsRecord();
        record.setUavId(status.getUavId());
        record.setDeviceId(status.getDeviceId());
        // 尝试关联订单号
        record.setOrderNum(resolveOrderNum(status));
        record.setLongitude(status.getLongitude());
        record.setLatitude(status.getLatitude());
        record.setAltitude(status.getAltitude());
        record.setSpeed(status.getSpeed());
        record.setBattery(status.getBattery());
        record.setFlightStatus(status.getFlightStatus());
        record.setOperation(status.getOperation());
        record.setTimestamp(status.getTimestamp());
        record.setReceivedAt(status.getReceivedAt());

        boolean offered = gpsBuffer.offer(record);
        if (!offered) {
            log.warn("GPS 缓冲队列已满（{}），丢弃最旧记录", gpsBuffer.size());
            gpsBuffer.poll(); // 丢弃最旧一条，为新数据腾空间
            gpsBuffer.offer(record);
        }
    }

    /**
     * 解析当前 GPS 点关联的订单号
     * 优先级：1. deviceOrderMap  2. 降级返回 null
     */
    private String resolveOrderNum(UavStatusDto status) {
        if (status.getDeviceId() != null) {
            String bound = deviceOrderMap.get(status.getDeviceId());
            if (bound != null) return bound;
        }
        return null;
    }

    /**
     * 定时批量写入 GPS 记录到数据库（每 5 秒或积累 200 条时触发）
     */
    @Scheduled(fixedDelay = 5000)
    public void flushGpsRecords() {
        if (gpsBuffer.isEmpty()) return;

        List<UavGpsRecord> batch = new ArrayList<>(BATCH_SIZE);
        gpsBuffer.drainTo(batch, BATCH_SIZE);

        if (!batch.isEmpty()) {
            try {
                gpsRecordRepository.saveAll(batch);
                log.debug("已批量写入 {} 条 GPS 记录", batch.size());
            } catch (Exception e) {
                log.error("批量写入 GPS 记录失败，丢弃 {} 条: {}", batch.size(), e.getMessage());
            }
        }
    }
}

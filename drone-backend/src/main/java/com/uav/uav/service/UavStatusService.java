package com.uav.uav.service;

import com.uav.uav.pojo.dto.UavStatusDto;
import com.uav.uav.pojo.entity.UavGpsRecord;
import com.uav.uav.pojo.vo.GpsPointVO;

import java.util.List;
import java.util.Map;

public interface UavStatusService {
    void updateUavStatus(UavStatusDto status);

    void updateUavStatus(String deviceId, UavStatusDto status);

    UavStatusDto getUavStatus(Long uavId);

    UavStatusDto getUavStatus(String deviceId);

    Map<Long, UavStatusDto> getAllUavStatus();

    Map<String, UavStatusDto> getAllUavStatusByDeviceId();

    /**
     * 绑定设备到订单（执行任务时调用），GPS 记录会自动带上订单号
     */
    void bindDeviceToOrder(String deviceId, String orderNum);

    /**
     * 解除设备与订单的绑定（任务完成后调用）
     */
    void clearDeviceOrderBinding(String deviceId);

    /**
     * 根据设备 ID 获取当前绑定的订单号
     */
    String getOrderNumByDevice(String deviceId);

    /**
     * 按订单号查询飞行轨迹，时间升序
     */
    List<UavGpsRecord> getTrajectoryByOrderNum(String orderNum);

    /**
     * 查询无人机最新位置（内存优先，内存无数据时降级 DB）
     * @param uavId    无人机 ID（与 deviceId 二选一）
     * @param deviceId 设备 ID（与 uavId 二选一）
     */
    GpsPointVO getLatestPosition(Long uavId, String deviceId);
}

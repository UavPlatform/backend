package com.uav.user.service;

import com.uav.aircraft.pojo.entity.AircraftModel;
import com.uav.user.pojo.entity.RiderUav;

import java.util.List;

public interface RiderUavService {

    /**
     * 绑定无人机。
     *
     * @param aircraftModelId 机型映射（{@code GET /api/aircraft-models} 返回的 id）；非空时校验
     *                        机型存在且启用（{@code AIRCRAFT_MODEL_NOT_FOUND}）。{@code null} 仅限
     *                        注册等未映射路径（V2 迁移存量兼容），该设备随后会被吊运应征门禁拦截。
     */
    void bindDrone(Long userId, String djiId, Long aircraftModelId);

    List<RiderUav> listDrones(Long userId);

    /**
     * 解绑无人机：按 {@code userId + djiId} 定位绑定；提交 {@code aircraftModelId} 时须与绑定记录
     * 一致（{@code AIRCRAFT_MODEL_MISMATCH}），未映射的存量绑定可不传机型直接解绑。
     */
    void unbindDrone(Long userId, String djiId, Long aircraftModelId);

    /**
     * 吊运应征设备门禁（REQ-BACKEND-001 完成条件，供 TASK-BACKEND-003 应征链路调用）：
     * 飞手必须绑定设备，且该设备已映射到 {@code aircraftModelId} 指定的可吊运机型，
     * 返回机型实体供计价读取系数。
     *
     * <p>错误码：机型未指定 → {@code AIRCRAFT_MODEL_REQUIRED}；机型不存在/停用 →
     * {@code AIRCRAFT_MODEL_NOT_FOUND}；不可吊运 → {@code AIRCRAFT_MODEL_NOT_TRANSPORTABLE}；
     * 未绑定设备 → {@code UAV_NOT_FOUND}；绑定设备未映射机型 → {@code AIRCRAFT_MODEL_REQUIRED}；
     * 已映射其他机型 → {@code AIRCRAFT_MODEL_MISMATCH}。
     */
    AircraftModel requireTransportDevice(Long userId, Long aircraftModelId);
}

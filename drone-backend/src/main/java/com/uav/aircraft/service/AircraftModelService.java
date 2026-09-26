package com.uav.aircraft.service;

import com.uav.aircraft.pojo.entity.AircraftModel;

import java.util.List;

/**
 * 平台机型目录服务（REQ-BACKEND-001 机型库）。
 */
public interface AircraftModelService {

    /** 机型目录（仅启用机型），供飞手绑机选择与客户端展示。 */
    List<AircraftModel> listEnabled();

    /**
     * 绑机校验：机型必须存在且启用。
     *
     * @throws com.uav.server.exception.BusinessException 机型不存在或已停用 → {@code AIRCRAFT_MODEL_NOT_FOUND}
     */
    AircraftModel requireSelectable(Long aircraftModelId);

    /**
     * 吊运应征门禁（ADR-0003：接单必须出示机型）：机型必须存在、启用且可吊运。
     *
     * <p>错误码（供 TASK-BACKEND-003 应征链路直接透出）：
     * <ul>
     *   <li>{@code null} → {@code AIRCRAFT_MODEL_REQUIRED}（未指定/设备未映射机型）</li>
     *   <li>不存在或已停用 → {@code AIRCRAFT_MODEL_NOT_FOUND}</li>
     *   <li>不可吊运 → {@code AIRCRAFT_MODEL_NOT_TRANSPORTABLE}</li>
     * </ul>
     */
    AircraftModel requireTransportCapable(Long aircraftModelId);
}

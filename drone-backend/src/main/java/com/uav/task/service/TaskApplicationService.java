package com.uav.task.service;

import com.uav.task.pojo.vo.TaskApplicationVO;

import java.util.List;

/**
 * 飞手应征与应征列表（REQ-BACKEND-001 / ADR-0003 多飞手应征 + 平台计价 SSOT）。
 *
 * <p>报价只来自服务端 {@link com.uav.server.calculator.TransportPriceCalculator}；
 * 客户端不提交任何金额字段，也不可改价。
 */
public interface TaskApplicationService {

    /**
     * 飞手应征：设备/机型门禁 → 平台计价 → 持久化（同任务同飞手重复应征=更新原记录并重新计价）。
     *
     * @param taskNum         任务编号
     * @param riderId         应征飞手（当前登录用户）
     * @param aircraftModelId 本次使用的机型
     * @return 应征记录（含系统报价 quotedAmount）
     */
    TaskApplicationVO apply(String taskNum, Long riderId, Long aircraftModelId);

    /**
     * 任务属主查询应征列表（按应征时间正序）；非属主被拒（FORBIDDEN）。
     *
     * @param taskNum 任务编号
     * @param userId  当前登录用户（必须是任务属主）
     */
    List<TaskApplicationVO> listByTask(String taskNum, Long userId);
}

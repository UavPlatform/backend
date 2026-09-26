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
     * 任务上下文应征列表（按应征时间正序）。
     *
     * <p>只读放行（TASK-BACKEND-007）：任务属主 / 应征飞手 / 管理员（role=2）；
     * 其余（非属主非应征的普通用户）→ FORBIDDEN。
     *
     * @param taskNum 任务编号
     * @param userId  当前登录用户 ID
     * @param role    当前登录角色（0 普通用户 / 1 飞手 / 2 管理员）
     */
    List<TaskApplicationVO> listByTask(String taskNum, Long userId, Integer role);
}

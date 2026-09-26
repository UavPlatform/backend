package com.uav.task.service;

import com.uav.task.pojo.dto.PriceEstimateDto;
import com.uav.task.pojo.dto.TaskDto;
import com.uav.task.pojo.entity.Task;
import com.uav.task.pojo.vo.PriceDetailVO;
import org.springframework.data.domain.Page;

import com.uav.task.pojo.vo.RiderStatsVO;

import java.time.LocalDateTime;
import java.util.List;

public interface TaskService {
    Task createTask(TaskDto dto);

    /** 发布前预览参考价（与 createTask 同口径，readOnly） */
    PriceDetailVO estimatePrice(PriceEstimateDto dto);

    Page<Task> getTasksByUser(Long userId, int page, int size);

    void deleteTask(Long id, Long userId);

    Task getTaskByTaskNum(String taskNum, Long userId);

    /** 飞手查看任务详情，不校验归属 */
    Task getTaskByTaskNum(String taskNum);

    /**
     * 任务广场（ADR-0003 闲鱼式撮合）：待撮合的空闲任务（SEEKING_RIDER / NEGOTIATING）。
     * 不再要求订单已支付——支付发生在用户选定应征之后。
     */
    List<Task> getAvailableTasks();

    /**
     * 用户选定应征并下单（POST /task/{taskNum}/select-rider，ADR-0003 决定 3）：
     * 应征置 SELECTED、其余置 CLOSED；订单锁定 {@code totalAmount = quotedAmount}（严格相等，禁止改价）
     * 与约定作业时间；未支付订单转 PENDING 待支付，已支付订单直接回 AWAITING_RIDER_CONFIRM（重新开放确认）。
     * 撮合状态非法迁移抛 MATCH_STATUS_INVALID。
     *
     * @param taskNum       任务编号（仅任务属主可操作）
     * @param applicationId 用户选定的应征记录 ID
     * @param scheduledTime 约定作业时间（必填，用户侧确认时间 = 下单时刻）
     */
    Task selectRider(String taskNum, Long userId, Long applicationId, LocalDateTime scheduledTime);

    /**
     * 飞手确认接单与约定时间（POST /rider/confirm-order，ADR-0003 决定 4）：
     * 记录 riderConfirmedAt、撮合状态 → CONFIRMED、登记接单记录，随后经
     * {@link #startExecution(String)} 双确认门禁推进 IN_PROGRESS。
     */
    Task riderConfirmOrder(String taskNum, Long riderId);

    /**
     * 双确认门禁（ADR-0003 决定 4）：matchStatus=CONFIRMED 且订单 PAID 且 userConfirmedAt/
     * riderConfirmedAt 齐备时才允许 TaskStatus → IN_PROGRESS（djifly 执飞链路的启动入口必须经此检查）。
     * 不满足时抛 DOUBLE_CONFIRM_REQUIRED，绝不静默放行。
     */
    Task startExecution(String taskNum);

    List<Task> getRiderActiveTasks(Long riderId);

    List<Task> getRiderAllTasks(Long riderId);

    void riderCancelTask(String taskNum, Long riderId);

    /**
     * 飞手完成任务（1B-9a）：note 为可选完成说明（≤500 字符，落 task_assignment.complete_note）。
     * TASK-BACKEND-004：必须已上传履约证据（attachment）才允许进入 PENDING_ACCEPTANCE，
     * 否则 DELIVERY_EVIDENCE_REQUIRED。
     */
    void riderCompleteTask(String taskNum, Long riderId, String note);

    /**
     * 用户确认收货结案（ADR-0003 决定 5）：任务 COMPLETED 且订单 WAITING_CONFIRM 且已上传履约证据；
     * 无证据拒绝（DELIVERY_EVIDENCE_REQUIRED），确认后 matchStatus → CLOSED、订单 → COMPLETED。
     */
    void userConfirmTask(String taskNum, Long userId);

    /** 飞手统计（今日接单、总完成、总收益） */
    RiderStatsVO getRiderStats(Long riderId);

    /** 推荐飞手列表（按完成量降序） */
    List<RiderStatsVO> getRecommendedRiders();
}

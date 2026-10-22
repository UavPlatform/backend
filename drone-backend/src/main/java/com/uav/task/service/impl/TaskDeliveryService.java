package com.uav.task.service.impl;

import com.uav.attachment.service.AttachmentService;
import com.uav.order.mapper.OrderRepository;
import com.uav.server.enums.ApiErrorCode;
import com.uav.server.enums.MatchStatus;
import com.uav.server.enums.OrderStatus;
import com.uav.server.enums.TaskStatus;
import com.uav.server.exception.BusinessException;
import com.uav.task.mapper.TaskAssignmentRepository;
import com.uav.task.mapper.TaskRepository;
import com.uav.task.pojo.entity.Task;
import com.uav.task.pojo.entity.TaskAssignment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Execution-phase transitions of the matching state machine (ADR-0003):
 * rider cancel (IN_PROGRESS → NEGOTIATING), rider delivery (→ PENDING_ACCEPTANCE)
 * and user acceptance (→ CLOSED). Delivery and acceptance require verified
 * evidence (ADR-0003 决定 5, {@code DELIVERY_EVIDENCE_REQUIRED}).
 */
@Service
@Slf4j
public class TaskDeliveryService {

    private final TaskRepository taskRepository;
    private final TaskAssignmentRepository taskAssignmentRepository;
    private final AttachmentService attachmentService;
    private final OrderRepository orderRepository;

    public TaskDeliveryService(TaskRepository taskRepository,
                               TaskAssignmentRepository taskAssignmentRepository,
                               AttachmentService attachmentService,
                               OrderRepository orderRepository) {
        this.taskRepository = taskRepository;
        this.taskAssignmentRepository = taskAssignmentRepository;
        this.attachmentService = attachmentService;
        this.orderRepository = orderRepository;
    }

    @Transactional(rollbackFor = Exception.class)
    public void riderCancelTask(String taskNum, Long riderId) {
        Task task = taskRepository.findByTaskNumForUpdate(taskNum)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.ROUTE_NOT_FOUND));

        TaskAssignment assignment = taskAssignmentRepository.findByTaskId(task.getId())
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.INVALID_PARAM, "接单记录不存在"));
        if (!assignment.getRiderId().equals(riderId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, ApiErrorCode.INVALID_PARAM, "无权取消此任务");
        }
        if (task.getTaskStatus() != TaskStatus.IN_PROGRESS) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_PARAM, "任务状态不允许取消");
        }

        // 撮合状态回退：执行中取消 → 重新开放撮合（订单保持已支付，可重新选定或由该飞手重新确认）
        MatchStatus.requireTransition(task.getMatchStatus(), MatchStatus.NEGOTIATING);
        task.setTaskStatus(TaskStatus.IDLE);
        task.setMatchStatus(MatchStatus.NEGOTIATING);
        taskRepository.save(task);
        taskAssignmentRepository.delete(assignment);

        log.info("骑手ID {} 取消任务 {}, 任务重新开放撮合", riderId, taskNum);
    }

    @Transactional(rollbackFor = Exception.class)
    public void riderCompleteTask(String taskNum, Long riderId, String note) {
        Task task = taskRepository.findByTaskNumForUpdate(taskNum)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.ROUTE_NOT_FOUND));

        TaskAssignment assignment = taskAssignmentRepository.findByTaskId(task.getId())
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.INVALID_PARAM, "接单记录不存在"));
        if (!assignment.getRiderId().equals(riderId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, ApiErrorCode.INVALID_PARAM, "无权完成此任务");
        }
        if (task.getTaskStatus() != TaskStatus.IN_PROGRESS) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_PARAM, "任务状态不允许完成");
        }
        String completeNote = null;
        if (note != null && !note.isBlank()) {
            String trimmed = note.trim();
            if (trimmed.length() > 500) {
                throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_PARAM, "完成说明不能超过 500 字");
            }
            completeNote = trimmed;
        }
        // 验收 5（ADR-0003 决定 5）：交付必须有履约证据，未上传 attachment 不得进入待验收
        requireEvidence(taskNum);

        MatchStatus.requireTransition(task.getMatchStatus(), MatchStatus.PENDING_ACCEPTANCE);
        task.setTaskStatus(TaskStatus.COMPLETED);
        task.setMatchStatus(MatchStatus.PENDING_ACCEPTANCE);
        taskRepository.save(task);

        assignment.setCompleteTime(LocalDateTime.now());
        assignment.setCompleteNote(completeNote);
        taskAssignmentRepository.save(assignment);

        orderRepository.findByTaskId(task.getId()).ifPresent(order -> {
            order.setExecutedAt(LocalDateTime.now());
            order.setOrderStatus(OrderStatus.WAITING_CONFIRM);
            orderRepository.save(order);
        });

        log.info("任务 {} 已交付（履约证据齐备），等待用户ID {} 确认", taskNum, task.getUserId());
    }

    @Transactional(rollbackFor = Exception.class)
    public void userConfirmTask(String taskNum, Long userId) {
        Task task = taskRepository.findByTaskNum(taskNum)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.ROUTE_NOT_FOUND));
        if (!task.getUserId().equals(userId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, ApiErrorCode.ROUTE_NOT_FOUND, "无权确认此任务");
        }
        if (task.getTaskStatus() != TaskStatus.COMPLETED) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_PARAM, "任务尚未完成，无法确认");
        }
        // 验收 5：无履约证据时用户确认完成必须拒绝（防伪造结案）
        requireEvidence(taskNum);

        orderRepository.findByTaskId(task.getId()).ifPresentOrElse(
                order -> {
                    if (order.getOrderStatus() != OrderStatus.WAITING_CONFIRM) {
                        throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.ORDER_STATUS_INVALID,
                                "订单状态不允许确认");
                    }
                    MatchStatus.requireTransition(task.getMatchStatus(), MatchStatus.CLOSED);
                    task.setMatchStatus(MatchStatus.CLOSED);
                    taskRepository.save(task);
                    order.setOrderStatus(OrderStatus.COMPLETED);
                    orderRepository.save(order);
                    log.info("用户ID {} 确认收货，订单 {} 已完成并结案", userId, order.getOrderNum());
                },
                () -> log.warn("任务 {} 未找到关联订单，跳过确认", taskNum)
        );
    }

    private void requireEvidence(String taskNum) {
        if (!attachmentService.hasVerifiedEvidence(taskNum)) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.DELIVERY_EVIDENCE_REQUIRED,
                    "尚未上传履约证据（照片/视频等），不能推进完成确认");
        }
    }
}

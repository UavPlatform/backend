package com.uav.admin.pojo.vo;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.uav.task.pojo.entity.Task;

import java.time.LocalDateTime;

/**
 * 1B-5 管理端任务契约：双状态（taskStatus × orderStatus）+ actionHint 操作提示，
 * 供运营端渲染 状态×操作 矩阵（全景 P1-8）。
 */
public record AdminTaskVo(
        Long id,
        String taskNum,
        String taskName,
        Long userId,
        String ownerName,
        String taskStatus,
        String taskStatusDesc,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime taskTime,
        Double reward,
        String description,
        String orderNum,
        Integer orderStatusCode,
        String orderStatus,
        String orderStatusDesc,
        java.math.BigDecimal totalAmount,
        java.math.BigDecimal totalDistance,
        String riderName,
        String completeNote,
        String actionHint,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime createTime,
        @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime updateTime) {

    public static AdminTaskVo of(Task task, String ownerName, String orderNum,
                                 Integer orderStatusCode, String orderStatus, String orderStatusDesc,
                                 java.math.BigDecimal totalAmount, java.math.BigDecimal totalDistance,
                                 String riderName, String completeNote, String actionHint) {
        return new AdminTaskVo(
                task.getId(),
                task.getTaskNum(),
                task.getTaskName(),
                task.getUserId(),
                ownerName,
                task.getTaskStatus().name(),
                task.getTaskStatus().getDescription(),
                task.getTaskTime(),
                task.getReward(),
                task.getDescription(),
                orderNum,
                orderStatusCode,
                orderStatus,
                orderStatusDesc,
                totalAmount,
                totalDistance,
                riderName,
                completeNote,
                actionHint,
                task.getCreateTime(),
                task.getUpdateTime());
    }
}

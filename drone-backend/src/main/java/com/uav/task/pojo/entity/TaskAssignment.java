package com.uav.task.pojo.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "task_assignment", indexes = {
        @Index(name = "idx_assign_rider_complete", columnList = "rider_id, complete_time"),
        @Index(name = "idx_assign_rider_accept", columnList = "rider_id, accept_time"),
        @Index(name = "idx_assign_task", columnList = "task_id")
})
public class TaskAssignment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_id", nullable = false)
    private Long taskId;

    @Column(name = "rider_id", nullable = false)
    private Long riderId;

    @Column(name = "accept_time", nullable = false)
    private LocalDateTime acceptTime;

    @Column(name = "complete_time")
    private LocalDateTime completeTime;

    /**
     * 飞手完成说明（1B-9a）：/rider/complete 可选文本，≤500 字符。
     * 1B-9b 交付物预留字段设计见 t32 任务 output（本轮不实现上传）。
     */
    @Column(name = "complete_note", length = 500)
    private String completeNote;
}

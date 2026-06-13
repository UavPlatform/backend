package com.uav.task.pojo.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "task_assignment")
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
}

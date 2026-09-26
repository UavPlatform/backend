package com.uav.task.pojo.entity;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.uav.server.enums.CargoCategory;
import com.uav.server.enums.TaskStatus;
import com.uav.server.enums.TaskType;
import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@Entity
@Table(name = "task")
public class Task {

    @PrePersist
    protected void onCreate() {
        this.createTime = LocalDateTime.now();
        this.updateTime = this.createTime;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updateTime = LocalDateTime.now();
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "task_num", unique = true, length = 64)
    private String taskNum;

    @Column(name = "task_name")
    private String taskName;

    @Column(name = "user_id")
    private Long userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "task_type")
    private TaskType taskType;

    @Enumerated(EnumType.STRING)
    @Column(name = "task_status")
    private TaskStatus taskStatus;

    @Column(name = "default_speed")
    private Double defaultSpeed;

    @Column(name = "default_height")
    private Double defaultHeight;

    @Column(name = "description")
    private String description;

    /**
     * 任务期望执行时间（1A-7a taskTime 契约修复，APP P0-4）。
     * 列由 ddl-auto=update 增量添加（schema 双轨现状下最小改动）。
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Column(name = "task_time")
    private LocalDateTime taskTime;

    /**
     * 吊运货物重量（kg）（V3__transport_application.sql）。ADR-0003 报价公式因子
     * {@code weightCharge = 重量 × transport.pricing.price-per-kg}。
     * 可空：存量任务与非吊运任务不填；吊运应征时缺重量会被拒绝计价。
     */
    @Column(name = "cargo_weight_kg", precision = 8, scale = 2)
    private BigDecimal cargoWeightKg;

    /**
     * 吊运货物类别（V3__transport_application.sql），与 {@code transport.pricing.category-surcharge}
     * 配置表对齐；可空 = 未知类别（按 unknown-category-surcharge 计费）。
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "cargo_category", length = 32)
    private CargoCategory cargoCategory;

    @Column(name = "reward")
    private Double reward;

    @Column(name = "create_time", nullable = false)
    private LocalDateTime createTime;

    @Column(name = "update_time")
    private LocalDateTime updateTime;

    @OneToMany(mappedBy = "task", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("orderIndex ASC")
    private List<TaskWaypoint> waypoints;
}

package com.uav.task.pojo.entity;

import com.uav.server.enums.ApplicationStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 飞手应征记录（REQ-BACKEND-001 撮合模型 / ADR-0003 决定 1「多飞手应征 + 用户选定」）。
 *
 * <p>每条绑定 {@code riderId}、{@code aircraftModelId}、{@code quotedAmount} 与应征时间；
 * 同一飞手对同一任务仅一条记录（{@code uk_task_application_task_rider}），重复应征即更新
 * 原记录并按平台规则重新计价。
 *
 * <p><b>ADR-0003「不允许改价」</b>：{@code quotedAmount} 只能由
 * {@link com.uav.server.calculator.TransportPriceCalculator} 计算写入，本实体不提供任何
 * 由客户端金额驱动的写入口；成交价必须严格等于该值。
 */
@Data
@Entity
@Table(name = "task_application",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_task_application_task_rider", columnNames = {"task_id", "rider_id"}))
public class TaskApplication {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 应征所属任务（{@code task.id}）。 */
    @Column(name = "task_id", nullable = false)
    private Long taskId;

    /** 应征飞手（{@code user.id}）。 */
    @Column(name = "rider_id", nullable = false)
    private Long riderId;

    /** 本次应征使用的机型（{@code aircraft_model.id}），影响载重门禁与报价系数。 */
    @Column(name = "aircraft_model_id", nullable = false)
    private Long aircraftModelId;

    /** 系统报价（ADR-0003：成交价必须严格等于该值，不允许改价）。 */
    @Column(name = "quoted_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal quotedAmount;

    /** 撮合子状态：ACTIVE=应征中；SELECTED/CLOSED 供 TASK-BACKEND-004 选定/失效语义。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private ApplicationStatus status;

    /** 应征时间（首次应征时间；重复应征不重置）。 */
    @Column(name = "create_time", nullable = false)
    private LocalDateTime createTime;

    /** 最近一次应征（重新计价）时间。 */
    @Column(name = "update_time")
    private LocalDateTime updateTime;

    @PrePersist
    protected void onCreate() {
        if (this.createTime == null) {
            this.createTime = LocalDateTime.now();
        }
        this.updateTime = this.createTime;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updateTime = LocalDateTime.now();
    }
}

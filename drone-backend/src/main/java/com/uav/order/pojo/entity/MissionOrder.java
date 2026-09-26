package com.uav.order.pojo.entity;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.uav.task.pojo.entity.Task;
import com.uav.server.enums.OrderStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "mission_order", indexes = {
        @Index(name = "idx_user_status", columnList = "user_id, order_status")
})
public class MissionOrder {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_num", unique = true, nullable = false, length = 64)
    private String orderNum;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "task_id", nullable = false,
            foreignKey = @ForeignKey(ConstraintMode.NO_CONSTRAINT))
    private Task task;

    @Column(name = "total_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "total_distance", nullable = false, precision = 10, scale = 2)
    private BigDecimal totalDistance;

    @Enumerated(EnumType.STRING)
    @Column(name = "order_status", nullable = false)
    private OrderStatus orderStatus;

    @Column(name = "pending_key", unique = true, length = 64)
    private String pendingKey;

    /**
     * 用户选定的应征记录（{@code task_application.id}，TASK-BACKEND-004 / ADR-0003 决定 2）。
     * 选定时刻由 select-rider 写入，同时锁定 {@code totalAmount = quotedAmount}；
     * 支付链路（/pay 与 handleNotify）据此硬校验金额，任何偏离一律
     * {@link com.uav.server.enums.ApiErrorCode#AMOUNT_MISMATCH} 拒绝（不允许改价）。
     */
    @Column(name = "selected_application_id")
    private Long selectedApplicationId;

    /**
     * 约定作业时间（ADR-0003 决定 4「用户确认约定时间」：下单时提交 scheduledTime）。
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    @Column(name = "scheduled_time")
    private LocalDateTime scheduledTime;

    /**
     * 用户下单（选定应征 + 约定时间）时刻 = 用户侧确认时间（ADR-0003 决定 4）。
     */
    @Column(name = "user_confirmed_at")
    private LocalDateTime userConfirmedAt;

    /**
     * 飞手确认接单与约定时间的时刻（ADR-0003 决定 4）。与 {@link #userConfirmedAt}
     * 同时存在才允许 TaskStatus → IN_PROGRESS（双确认门禁）。
     */
    @Column(name = "rider_confirmed_at")
    private LocalDateTime riderConfirmedAt;


    @Column(name = "create_time", nullable = false, updatable = false)
    private LocalDateTime createTime;

    @Version
    @Column(name = "version")
    private Long version;

    @Column(name = "update_time")
    private LocalDateTime updateTime;

    @Column(name = "executed_at")
    private LocalDateTime executedAt;

    @Column(name = "execute_result", length = 32)
    private String executeResult;

    @PrePersist
    protected void onCreate() {
        this.createTime = LocalDateTime.now();
        this.updateTime = this.createTime;
        if (this.orderStatus == null) {
            this.orderStatus = OrderStatus.PENDING;
        }
        syncPendingKey();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updateTime = LocalDateTime.now();
        syncPendingKey();
    }

    private void syncPendingKey() {
        this.pendingKey = (this.orderStatus == OrderStatus.PENDING && this.userId != null)
                ? String.valueOf(this.userId) : null;
    }
}

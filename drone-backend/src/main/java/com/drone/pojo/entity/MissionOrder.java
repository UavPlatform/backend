package com.drone.pojo.entity;

import com.drone.pojo.enums.OrderStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "mission_order", indexes = {
        @Index(name = "idx_user_status", columnList = "user_name, order_status")
})
public class MissionOrder {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_num", unique = true, nullable = false, length = 64)
    private String orderNum;

    @Column(name = "user_name", nullable = false)
    private String userName;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "route_id", nullable = false)
    private Route route;

    @Column(name = "dji_id", nullable = false)
    private String djiId;

    @Column(name = "total_amount", nullable = false, precision = 10, scale = 2)
    private BigDecimal totalAmount;

    @Column(name = "total_distance", nullable = false, precision = 10, scale = 2)
    private BigDecimal totalDistance;

    @Enumerated(EnumType.STRING)
    @Column(name = "order_status", nullable = false)
    private OrderStatus orderStatus;

    /**
     * 唯一索引防并发重复下单：PENDING 时 = userName，非 PENDING 时 = NULL（允许多个 NULL）
     */
    @Column(name = "pending_key", unique = true, length = 64)
    private String pendingKey;

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
        this.pendingKey = (this.orderStatus == OrderStatus.PENDING) ? this.userName : null;
    }
}

package com.uav.pay.pojo.entity;

import com.uav.server.enums.OrderStatus;
import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "pay_record", indexes = {
        @Index(name = "idx_order_num", columnList = "order_num"),
        @Index(name = "idx_user_id", columnList = "user_id"),
        @Index(name = "idx_transaction_id", columnList = "transaction_id"),
        @Index(name = "idx_status", columnList = "status"),
        @Index(name = "idx_create_time", columnList = "create_time")
})
public class PayRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_num", nullable = false, length = 64)
    private String orderNum;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal amount;

    @Column(name = "pay_channel", length = 32)
    private String payChannel;

    @Column(name = "prepay_id", length = 64)
    private String prepayId;

    @Column(name = "transaction_id", length = 64)
    private String transactionId;

    @Column(name = "status", length = 32)
    @Enumerated(EnumType.STRING)
    private OrderStatus status;

    @Column(name = "error_msg", length = 512)
    private String errorMsg;

    @Column(name = "callback_body", columnDefinition = "TEXT")
    private String callbackBody;

    @Column(name = "create_time", nullable = false)
    private LocalDateTime createTime;

    @Column(name = "pay_time")
    private LocalDateTime payTime;

    @Column(name = "update_time")
    private LocalDateTime updateTime;

    @Version
    private Integer version;

    @PrePersist
    protected void onCreate() {
        this.createTime = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updateTime = LocalDateTime.now();
    }
}

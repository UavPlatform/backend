package com.uav.order.pojo.entity;

import com.uav.server.enums.ComplaintReason;
import com.uav.server.enums.ComplaintStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "order_complaint")
public class OrderComplaint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 关联订单号 */
    @Column(name = "order_num", unique = true, nullable = false, length = 64)
    private String orderNum;

    /** 投诉用户 */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 投诉原因 */
    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 32)
    private ComplaintReason reason;

    /** 详细描述 */
    @Column(name = "description", length = 1024)
    private String description;

    /** 处理状态 */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private ComplaintStatus status;

    /** 管理员处理备注 */
    @Column(name = "admin_note", length = 512)
    private String adminNote;

    /** 退款金额（默认全额） */
    @Column(name = "refund_amount", precision = 10, scale = 2)
    private BigDecimal refundAmount;

    @Column(name = "create_time", nullable = false, updatable = false)
    private LocalDateTime createTime;

    @Column(name = "resolve_time")
    private LocalDateTime resolveTime;

    @PrePersist
    protected void onCreate() {
        this.createTime = LocalDateTime.now();
        if (this.status == null) {
            this.status = ComplaintStatus.PENDING;
        }
    }
}

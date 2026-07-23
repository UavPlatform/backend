package com.uav.order.pojo.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "order_review")
public class OrderReview {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 关联订单号（一个订单只能评价一次） */
    @Column(name = "order_num", unique = true, nullable = false, length = 64)
    private String orderNum;

    /** 评价用户 */
    @Column(name = "user_id", nullable = false)
    private Long userId;

    /** 评分 1-5 */
    @Column(name = "rating", nullable = false)
    private int rating;

    /** 评价内容 */
    @Column(name = "content", length = 512)
    private String content;

    @Column(name = "create_time", nullable = false, updatable = false)
    private LocalDateTime createTime;

    @PrePersist
    protected void onCreate() {
        this.createTime = LocalDateTime.now();
    }
}

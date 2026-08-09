package com.uav.task.pojo.entity;

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

    @Column(name = "reward")
    private Double reward;

    /** 货物重量（kg），空 = 未填写 */
    @Column(name = "weight")
    private Double weight;

    /** 平台参考价（元，2 位小数） */
    @Column(name = "reference_price", precision = 10, scale = 2)
    private BigDecimal referencePrice;

    /** 计费明细 JSON（PriceDetailVO 序列化），展示用 */
    @Column(name = "price_detail", length = 1000)
    private String priceDetail;

    /** 计划执行时间（夜间附加费判断口径） */
    @Column(name = "planned_time")
    private LocalDateTime plannedTime;

    /** 超重等场景：true = 需平台人工报价 */
    @Column(name = "need_manual_quote")
    private Boolean needManualQuote = false;

    @Column(name = "create_time", nullable = false)
    private LocalDateTime createTime;

    @Column(name = "update_time")
    private LocalDateTime updateTime;

    @OneToMany(mappedBy = "task", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("orderIndex ASC")
    private List<TaskWaypoint> waypoints;
}

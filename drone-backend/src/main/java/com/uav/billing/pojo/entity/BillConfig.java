package com.uav.billing.pojo.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 计费配置表：所有商业数值（费率、阶梯、夜间时段、砍价下限等）集中存放，
 * 运营可后台调整，无需重启。代码中只保留默认值兜底。
 */
@Data
@Entity
@Table(name = "bill_config")
public class BillConfig {

    @PrePersist
    @PreUpdate
    protected void onSave() {
        this.updateTime = LocalDateTime.now();
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "config_key", unique = true, nullable = false, length = 64)
    private String configKey;

    @Column(name = "config_value", nullable = false, length = 1000)
    private String configValue;

    @Column(name = "description", length = 255)
    private String description;

    @Column(name = "enabled", nullable = false)
    private Boolean enabled = true;

    @Column(name = "update_time")
    private LocalDateTime updateTime;
}

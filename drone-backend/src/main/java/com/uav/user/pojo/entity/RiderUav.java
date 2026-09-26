package com.uav.user.pojo.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "rider_uav")
public class RiderUav {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "dji_id", nullable = false, length = 64)
    private String djiId;

    /**
     * 机型映射（V2 迁移可空）：绑定时提交 aircraftModelId 即落库；
     * 未映射（null，如注册旧路径的存量绑定）的设备不得用于吊运应征。
     */
    @Column(name = "aircraft_model_id")
    private Long aircraftModelId;

    @Column(name = "create_time", nullable = false, updatable = false)
    private LocalDateTime createTime;

    @PrePersist
    protected void onCreate() {
        this.createTime = LocalDateTime.now();
    }
}

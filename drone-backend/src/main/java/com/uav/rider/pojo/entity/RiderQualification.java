package com.uav.rider.pojo.entity;

import com.uav.server.enums.LicenseGrade;
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
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 飞手驾驶资质（{@code rider_qualification}，V6 迁移）。
 *
 * <p>资质维度对齐平台机型库（{@code aircraft_model}）：一条资质 = 飞手 × 机型 × 执照等级，
 * 同一飞手同一机型唯一（{@code uk_rider_qualification_user_model}）。
 */
@Data
@Entity
@Table(name = "rider_qualification")
public class RiderQualification {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "aircraft_model_id", nullable = false)
    private Long aircraftModelId;

    @Enumerated(EnumType.STRING)
    @Column(name = "license_grade", nullable = false, length = 32)
    private LicenseGrade licenseGrade;

    @Column(name = "create_time", nullable = false, updatable = false)
    private LocalDateTime createTime;

    @Column(name = "update_time")
    private LocalDateTime updateTime;

    @PrePersist
    protected void onCreate() {
        this.createTime = LocalDateTime.now();
        this.updateTime = this.createTime;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updateTime = LocalDateTime.now();
    }
}

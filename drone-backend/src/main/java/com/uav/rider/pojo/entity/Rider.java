package com.uav.rider.pojo.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Data;
import lombok.ToString;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 飞手资料（{@code rider} 表，主键与 {@code user.id} 相同，按需在首次保存资料时创建）。
 *
 * <p>身份证号 {@link #idNumber} 为敏感数据：仅服务端持有，任何接口只返回脱敏值
 * （见 {@code IdNumbers#mask}），且不参与 {@code toString}，避免进入日志。
 */
@Data
@Entity
@Table(name = "rider")
public class Rider {

    /** 与 user.id 相同。 */
    @Id
    private Long id;

    /** 历史字段（baseline 已有列），当前接口不读写。 */
    @Column(name = "name")
    private String name;

    @Column(name = "age")
    private Integer age;

    @Column(name = "location")
    private String location;

    @Column(name = "self_introduction")
    private String selfIntroduction;

    /** 身份证号（敏感，规范化为大写 X）。 */
    @ToString.Exclude
    @Column(name = "id_number", length = 32)
    private String idNumber;

    /** 证件有效期截止日（长期有效证件可不填）。 */
    @Column(name = "id_expiry_date")
    private LocalDate idExpiryDate;

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

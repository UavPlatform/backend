package com.uav.rider.pojo.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class Rider {

    @Setter
    @Id
    private Long id;

    @Setter
    private Integer age;

    @Setter
    @Column(name = "cert_number", length = 64)
    private String certNumber;

    @Setter
    @Column(name = "cert_valid_from")
    private LocalDate certValidFrom;

    @Setter
    @Column(name = "cert_valid_until")
    private LocalDate certValidUntil;

    @Setter
    private String location;

    @Setter
    @Column(name = "self_intro", length = 500)
    private String selfIntroduction;

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

    public Long getId() { return id; }
    public Integer getAge() { return age; }
    public String getCertNumber() { return certNumber; }
    public LocalDate getCertValidFrom() { return certValidFrom; }
    public LocalDate getCertValidUntil() { return certValidUntil; }
    public String getLocation() { return location; }
    public String getSelfIntroduction() { return selfIntroduction; }
    public LocalDateTime getCreateTime() { return createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }
}

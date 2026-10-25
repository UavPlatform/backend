package com.uav.dispatch.pojo.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

@Entity
@Table(name = "waybill_assessment")
@Getter
@Setter
public class WaybillAssessment {
    @Id @Column(length = 36) private String id;
    @Column(nullable = false) private Long userId;
    @Column(nullable = false, length = 64) private String fingerprint;
    @Column(nullable = false, columnDefinition = "LONGTEXT") private String fieldsJson;
    @Column(nullable = false, columnDefinition = "LONGTEXT") private String evaluationJson;
    @Column(nullable = false) private LocalDateTime expiresAt;
    @Column(columnDefinition = "LONGTEXT") private String quoteJson;
}

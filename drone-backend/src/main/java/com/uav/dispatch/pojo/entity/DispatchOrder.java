package com.uav.dispatch.pojo.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** REQ-SHIP-PUB-003: separate dispatch state and deposit state; never an old quoted order. */
@Entity
@Table(name = "dispatch_order", uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "request_key"}))
@Getter
@Setter
public class DispatchOrder {
    @Id @Column(length = 36) private String id;
    @Version private Long revision;
    @Column(nullable = false) private Long userId;
    @Column(nullable = false, length = 64) private String requestKey;
    @Column(nullable = false, length = 36) private String assessmentId;
    @Column(nullable = false, length = 64) private String fingerprint;
    @Column(nullable = false, columnDefinition = "LONGTEXT") private String fieldsJson;
    @Column(nullable = false, precision = 10, scale = 2) private BigDecimal depositAmount;
    @Column(nullable = false, precision = 10, scale = 2) private BigDecimal pilotServiceFee;
    @Column(nullable = false, length = 32) private String stage;
    @Column(nullable = false, length = 32) private String fundsStatus;
    @Column(length = 64) private String paymentReference;
    @Column(length = 64) private String refundReference;
    @Column(nullable = false) private LocalDateTime createdAt;
    @Column(nullable = false) private LocalDateTime updatedAt;
    @Column private LocalDateTime nearbyWaitingSince;
    @Column private Long assignedPilotId;
    @Column(nullable = false) private long assignmentVersion;
    @Column(columnDefinition = "LONGTEXT") private String quoteJson;
    @Column private LocalDateTime startedAt;
    @Column private LocalDateTime deliveredAt;
    @Column private LocalDateTime receiptAt;
    @Column private LocalDateTime completedAt;
    @Column private LocalDateTime settledAt;
    @Column(columnDefinition = "LONGTEXT") private String pilotMaterialIdsJson;
    @Column(columnDefinition = "LONGTEXT") private String receiptMaterialIdsJson;
    @Column(precision = 10, scale = 2) private BigDecimal balanceAmount;
    @Column(length = 64) private String settlementReference;
    @Column(length = 64) private String executedSerialNumber;
    @Column(length = 128) private String executedModelCode;
    @Column(length = 128) private String executedModelName;
    @Column private Long executedAircraftModelId;
    @Column private Integer reviewScore;
    @Column(length = 500) private String reviewComment;
}

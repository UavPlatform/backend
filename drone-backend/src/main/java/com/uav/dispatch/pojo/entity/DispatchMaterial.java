package com.uav.dispatch.pojo.entity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

@Entity @Table(name = "dispatch_material") @Getter @Setter
public class DispatchMaterial {
    @Id @Column(length = 36) private String id;
    @Column(nullable = false, length = 36) private String orderId;
    @Column(nullable = false) private Long uploaderId;
    @Column(nullable = false) private long assignmentVersion;
    @Column(nullable = false, length = 32) private String purpose;
    @Column(nullable = false, unique = true) private String objectKey;
    @Column(nullable = false) private String fileName;
    @Column(nullable = false, length = 64) private String contentType;
    @Column(nullable = false) private long sizeBytes;
    @Column(nullable = false) private boolean uploaded;
    @Column(nullable = false) private LocalDateTime createdAt;
    @Column private LocalDateTime confirmedAt;
}

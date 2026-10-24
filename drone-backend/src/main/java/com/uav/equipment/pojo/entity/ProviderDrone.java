package com.uav.equipment.pojo.entity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity @Table(name = "provider_drone") @Getter @Setter
public class ProviderDrone {
    @Id @Column(length = 36) private String id;
    @Column(nullable = false) private Long providerId;
    @Column(nullable = false) private Long aircraftModelId;
    @Column(nullable = false, unique = true, length = 64) private String serialNumber;
    @Column(nullable = false, length = 100) private String airportName;
    @Column(nullable = false, precision = 9, scale = 6) private BigDecimal latitude;
    @Column(nullable = false, precision = 10, scale = 6) private BigDecimal longitude;
    @Column(nullable = false) private boolean enabled = true;
    @Column(unique = true, length = 36) private String currentBorrowId;
    @Column(nullable = false) private LocalDateTime createdAt;
}

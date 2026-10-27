package com.uav.equipment.pojo.entity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

/** Platform-owned rental drone (ADR-0011 / ADR-0012: binds airport_id; no lat/lng on drone). Table name kept from V15. */
@Entity @Table(name = "provider_drone") @Getter @Setter
public class ProviderDrone {
    @Id @Column(length = 36) private String id;
    @Column(nullable = false) private Long aircraftModelId;
    @Column(nullable = false, unique = true, length = 64) private String serialNumber;
    @Column(nullable = false) private Long airportId;
    @Column(nullable = false) private boolean enabled = true;
    @Column(unique = true, length = 36) private String currentBorrowId;
    @Column(nullable = false) private LocalDateTime createdAt;
}

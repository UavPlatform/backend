package com.uav.equipment.pojo.entity;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

@Entity @Table(name = "equipment_provider") @Getter @Setter
public class EquipmentProvider {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(nullable = false, unique = true, length = 64) private String username;
    @Column(nullable = false, length = 100) private String passwordHash;
    @Column(nullable = false, length = 100) private String displayName;
    @Column(nullable = false) private boolean enabled = true;
    @Column(nullable = false) private LocalDateTime createdAt;
}

package com.uav.rider.pojo.entity;

import com.uav.server.enums.AircraftCategory;
import com.uav.server.enums.LicenseGrade;
import com.uav.server.enums.UavWeight;
import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "rider_aircraft_qualification", indexes = {
        @Index(name = "idx_qual_user_id", columnList = "user_id")
})
@Builder
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class RiderAircraftQualification {

    @Setter
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Setter
    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Setter
    @Enumerated(EnumType.STRING)
    @Column(name = "category", nullable = false, length = 32)
    private AircraftCategory category;

    @Setter
    @Enumerated(EnumType.STRING)
    @Column(name = "license", nullable = false, length = 32)
    private LicenseGrade license;

    @Setter
    @Enumerated(EnumType.STRING)
    @Column(name = "weight", nullable = false, length = 32)
    private UavWeight weight;

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public AircraftCategory getCategory() { return category; }
    public LicenseGrade getLicense() { return license; }
    public UavWeight getWeight() { return weight; }
}

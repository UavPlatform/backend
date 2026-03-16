package com.drone.pojo.entity;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Table(name = "uav")
@Data
public class Uav {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "uav_name")
    private String uavName;

    @Column(name = "online_status")
    private Character onlineStatus; // 0离线, 1在线
}
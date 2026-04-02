package com.drone.pojo.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

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

    @Column(name = "uav_create_time")
    private LocalDateTime uavCreateTime;

    @Column(name = "dji_id")
    private String djiId;

    @Column(name = "controller_model")
    private String controllerModel;

    @Column(name ="is_available")
    private Character isAvailable;

}
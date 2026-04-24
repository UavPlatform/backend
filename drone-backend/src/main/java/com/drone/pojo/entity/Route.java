package com.drone.pojo.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Entity
@Table(name = "route")
public class Route {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "route_name", nullable = false)
    private String routeName;

    @Column(name = "user_name")
    private String userName;

    @Column(name = "dji_id")
    private String djiId;

    @Column(name = "default_speed", nullable = false)
    private Double defaultSpeed;

    @Column(name = "default_height", nullable = false)
    private Double defaultHeight;

    @Column(name = "description")
    private String description;

    @Column(name = "create_time", nullable = false)
    private LocalDateTime createTime;

    @Column(name = "update_time")
    private LocalDateTime updateTime;

    @OneToMany(mappedBy = "route", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("orderIndex ASC")
    private List<RouteWaypoint> waypoints;
}
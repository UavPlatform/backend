package com.drone.pojo.entity;

import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name = "route_waypoint")
public class RouteWaypoint {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "route_id", nullable = false)
    private Route route;

    @Column(name = "waypoint_order", nullable = false)
    private Integer orderIndex; // 路径点顺序

    @Column(name = "longitude", nullable = false)
    private Double longitude; // 经度

    @Column(name = "latitude", nullable = false)
    private Double latitude; // 纬度

    @Column(name = "altitude")
    private Double altitude; // 高度（可选，可使用航线默认高度）

    @Column(name = "stay_time")
    private Integer stayTime; // 停留时间（秒，可选）
}
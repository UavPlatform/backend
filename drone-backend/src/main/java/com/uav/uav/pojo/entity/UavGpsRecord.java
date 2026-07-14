package com.uav.uav.pojo.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "uav_gps_record", indexes = {
        @Index(name = "idx_gps_order_num", columnList = "order_num"),
        @Index(name = "idx_gps_uav_ts", columnList = "uav_id, timestamp"),
        @Index(name = "idx_gps_received_at", columnList = "received_at")
})
public class UavGpsRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 无人机 ID */
    @Column(name = "uav_id")
    private Long uavId;

    /** 设备标识（DJI SN 或自定义 deviceId） */
    @Column(name = "device_id", length = 64)
    private String deviceId;

    /** 关联订单号（无人机未执行任务时为 null） */
    @Column(name = "order_num", length = 64)
    private String orderNum;

    /** 经度 */
    @Column(name = "longitude", nullable = false)
    private double longitude;

    /** 纬度 */
    @Column(name = "latitude", nullable = false)
    private double latitude;

    /** 高度（米） */
    @Column(name = "altitude")
    private double altitude;

    /** 速度（m/s） */
    @Column(name = "speed")
    private double speed;

    /** 电池电量（0-100） */
    @Column(name = "battery")
    private int battery;

    /** 飞行状态：0=地面，1=飞行中 */
    @Column(name = "flight_status")
    private int flightStatus;

    /** 当前操作描述（如 EXECUTING_TASK, RETURNING 等） */
    @Column(name = "operation", length = 64)
    private String operation;

    /** 设备端时间戳（毫秒） */
    @Column(name = "timestamp", nullable = false)
    private long timestamp;

    /** 服务端接收时间（毫秒） */
    @Column(name = "received_at", nullable = false)
    private long receivedAt;

    @PrePersist
    protected void onCreate() {
        if (this.receivedAt <= 0) {
            this.receivedAt = System.currentTimeMillis();
        }
    }
}

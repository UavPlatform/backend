package com.uav.task.pojo.vo;

import lombok.Data;

@Data
public class RiderStatsVO {
    private Long riderId;         // 骑手ID（推荐列表时填充）
    private String riderName;     // 骑手用户名（推荐列表时填充）
    private long todayOrders;     // 今日接单数
    private long totalCompleted;  // 总完成任务数
    private Double totalEarnings; // 总收益（元）
}

package com.uav.uav.pojo.vo;

import lombok.Data;

@Data
public class AdminStatisticsVO {
    private int totalUavs;
    private int onlineUavs;
    private int availableUavs;
    private int liveUavs;
    private int offlineUavs;
    private int unavailableUavs;
    private long totalUsers;
}

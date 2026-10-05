package com.uav.order.pojo.vo;

import lombok.Data;

@Data
public class TradeStatsVO {
    private long totalOrderCount;      // 累计订单数（我的交易：我买的 ∪ 我卖的，含待撮合/已取消/已退款）
    private long completedTradeCount;  // 已完成交易数（仅「已完成」状态，待验收/争议中/已退款均不计）
    private long publishedTaskCount;   // 我发布的任务数
    private long boughtOrderCount;     // 我买到的订单数
    private long soldOrderCount;       // 我卖出的订单数（被选定的应征属于我）
    private long pendingReviewCount;   // 待评价订单数（已完成且我尚未评价）
}

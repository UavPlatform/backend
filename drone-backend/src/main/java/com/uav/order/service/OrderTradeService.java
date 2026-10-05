package com.uav.order.service;

import com.uav.order.pojo.entity.MissionOrder;
import com.uav.order.pojo.vo.TradeStatsVO;
import org.springframework.data.domain.Page;

/**
 * App「我的交易」查询：买家（我买到的）与卖方（我卖出的）两个视角的订单列表，以及各入口的统计计数。
 *
 * <p>卖方口径 = 订单上的成交指针 {@code selectedApplicationId} 指向该用户的应征（ADR-0003 决定 3：
 * 用户选定应征时写入并锁定金额，重新选定会改指）。
 */
public interface OrderTradeService {

    /** 我的交易（我买到的 ∪ 我卖出的），按创建时间倒序。status 为空表示不过滤。 */
    Page<MissionOrder> listMyTrades(Long userId, int page, int size, String status);

    /** 我卖出的（被选定的应征属于我），按创建时间倒序。status 为空表示不过滤。 */
    Page<MissionOrder> listSoldOrders(Long userId, int page, int size, String status);

    /** 待评价（已完成且我尚未评价），按创建时间倒序。 */
    Page<MissionOrder> listPendingReviewOrders(Long userId, int page, int size);

    /** 各入口统计计数（累计订单、已完成交易、我发布的、我买到的、我卖出的、待评价）。 */
    TradeStatsVO getTradeStats(Long userId);
}

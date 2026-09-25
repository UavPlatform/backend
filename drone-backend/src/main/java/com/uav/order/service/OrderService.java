package com.uav.order.service;

import com.uav.order.pojo.entity.MissionOrder;
import org.springframework.data.domain.Page;

public interface OrderService {

    /**
     * 创建 PENDING 订单并绑定服务端计价金额。
     *
     * <p>金额一律<b>服务端</b>计算，客户端传入的 reward 不作为金额依据：
     * 由 TaskServiceImpl 计价块按 bill_config（起步价 + 里程费 + 重量阶梯费 + 夜间附加费）
     * 算出参考价，再取「协商价 ?: 参考价」为挂牌价，并校验协商价不低于
     * 参考价 × MIN_NEGOTIATED_RATE。本方法只负责透传与校验，不再自行计价。
     *
     * @param listedPrice 挂牌价（元）= 协商价 ?: 参考价，等价于 Task.reward 与
     *                    MissionOrder.totalAmount。必须 &gt; 0。
     */
    MissionOrder createOrder(Long userId, String taskNum, Double listedPrice);

    Page<MissionOrder> listOrders(Long userId, int page, int size);

    MissionOrder getOrderDetail(String orderNum, Long userId);

    void cancelOrder(String orderNum, Long userId);

    /**
     * 更新订单的 executeResult（文件上传后绑定）
     * @param orderNum 订单号
     * @param resultUuid 32 字符 UUID（去横杠），作为文件目录标识
     */
    void updateExecuteResult(String orderNum, String resultUuid);

    /**
     * 用户对交付结果不满意，将订单置为争议中
     * @param orderNum 订单号
     * @param userId   用户 ID
     */
    void disputeOrder(String orderNum, Long userId);
}

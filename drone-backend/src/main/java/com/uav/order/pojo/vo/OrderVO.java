package com.uav.order.pojo.vo;

import com.uav.order.pojo.entity.MissionOrder;
import com.uav.upload.vo.UploadVO;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

@Data
public class OrderVO {

    private String orderNum;
    private BigDecimal totalAmount;
    private BigDecimal distance;
    private String taskName;
    private int orderStatus;
    /** 飞手完成任务的时间 */
    private LocalDateTime executedAt;
    /** 交付文件目录 UUID */
    private String executeResult;
    /** 交付文件列表 */
    private List<UploadVO> files;
    /** 是否已评价（Phase 3 用） */
    private boolean hasReview;

    public static OrderVO from(MissionOrder order) {
        OrderVO vo = new OrderVO();
        vo.setOrderNum(order.getOrderNum());
        vo.setTotalAmount(order.getTotalAmount());
        vo.setDistance(order.getTotalDistance());
        vo.setTaskName(order.getTask() != null ? order.getTask().getTaskName() : null);
        vo.setOrderStatus(order.getOrderStatus().getCode());
        vo.setExecutedAt(order.getExecutedAt());
        vo.setExecuteResult(order.getExecuteResult());
        vo.setFiles(Collections.emptyList());
        vo.setHasReview(false);
        return vo;
    }
}

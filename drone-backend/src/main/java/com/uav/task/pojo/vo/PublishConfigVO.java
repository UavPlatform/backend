package com.uav.task.pojo.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/** 发布页配置：控制重量输入是否展示、协商价下限 */
@Data
public class PublishConfigVO {
    /** 按重量计费的任务类型（默认仅 TRANSPORT） */
    private List<String> weightTypes;
    /** 协商价最低为参考价的倍数（默认 0.5） */
    private BigDecimal minNegotiatedRate;
}

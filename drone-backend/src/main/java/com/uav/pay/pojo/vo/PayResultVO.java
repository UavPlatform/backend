package com.uav.pay.pojo.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class PayResultVO {
    private String orderNum;
    private String prepayId;
    private String payUrl;
}

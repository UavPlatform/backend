package com.uav.order.pojo.vo;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class SubmitReviewVO {
    private String orderNum;
    private int rating;
}

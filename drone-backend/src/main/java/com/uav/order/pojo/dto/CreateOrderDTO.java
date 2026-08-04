package com.uav.order.pojo.dto;

import com.uav.server.enums.ApiErrorCode;
import com.uav.server.exception.BusinessException;
import lombok.Data;
import org.springframework.http.HttpStatus;

@Data
public class CreateOrderDTO {
    private String taskNum;
    private Double reward;

    public void validate() {
        if (taskNum == null || taskNum.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    ApiErrorCode.INVALID_PARAM, "taskNum 不能为空");
        }
        if (reward == null || reward <= 0) {
            throw new BusinessException(HttpStatus.BAD_REQUEST,
                    ApiErrorCode.INVALID_PARAM, "reward 必须大于0");
        }
    }
}

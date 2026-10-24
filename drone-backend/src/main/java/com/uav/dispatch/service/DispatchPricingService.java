package com.uav.dispatch.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.dispatch.pojo.vo.DispatchQuoteVO;
import com.uav.server.calculator.RoutePriceCalculator;
import com.uav.server.config.DispatchPaymentConfig;
import com.uav.server.config.OrderConfig;
import com.uav.server.enums.ApiErrorCode;
import com.uav.server.exception.BusinessException;
import com.uav.task.pojo.entity.TaskWaypoint;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;

@Service
public class DispatchPricingService {
    private static final ObjectMapper JSON = new ObjectMapper();
    private final OrderConfig distanceConfig;
    private final DispatchPaymentConfig paymentConfig;
    public DispatchPricingService(OrderConfig distanceConfig, DispatchPaymentConfig paymentConfig) {
        this.distanceConfig = distanceConfig;
        this.paymentConfig = paymentConfig;
    }
    public DispatchQuoteVO quote(Map<String, String> fields) {
        BigDecimal rate = distanceConfig.getPricePerMeter();
        if (rate == null || rate.signum() <= 0) throw new BusinessException(ApiErrorCode.DISPATCH_UNAVAILABLE);
        var start = point(fields, "start"); var end = point(fields, "end");
        BigDecimal distance = RoutePriceCalculator.calculateTotalDistance(List.of(start, end));
        BigDecimal charge = RoutePriceCalculator.calculatePrice(distance, rate);
        BigDecimal fee = paymentConfig.getPilotServiceFee().setScale(2, RoundingMode.HALF_UP);
        BigDecimal total = charge.add(fee);
        if (total.compareTo(new BigDecimal("99999999.99")) > 0) throw new BusinessException(ApiErrorCode.INVALID_PARAM);
        return new DispatchQuoteVO("DISTANCE_V1", "START_END_GEODESIC", distance, rate, charge, fee,
                total, paymentConfig.getDepositAmount());
    }
    public static String encode(DispatchQuoteVO quote) {
        try { return JSON.writeValueAsString(quote); }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException(e); }
    }
    public static DispatchQuoteVO decode(String value) {
        if (value == null) return null;
        try { return JSON.readValue(value, DispatchQuoteVO.class); }
        catch (com.fasterxml.jackson.core.JsonProcessingException e) { throw new IllegalStateException(e); }
    }
    private TaskWaypoint point(Map<String, String> fields, String prefix) {
        var point = new TaskWaypoint();
        point.setLatitude(Double.valueOf(fields.get(prefix + ".lat")));
        point.setLongitude(Double.valueOf(fields.get(prefix + ".lng")));
        return point;
    }
}

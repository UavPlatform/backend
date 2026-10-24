package com.uav.dispatch.pojo.vo;

import java.util.List;

public record DispatchReadinessVO(boolean orderingEnabled, String paymentMode, List<String> blockers) { }

package com.uav.billing.service;

import com.uav.billing.pojo.entity.BillConfig;
import com.fasterxml.jackson.core.type.TypeReference;

import java.math.BigDecimal;
import java.util.List;

public interface BillConfigService {

    /** 取数值配置；缺失/禁用/解析失败 → 返回默认值（记 warn 日志），不抛异常 */
    Double getDouble(String key, double defaultValue);

    Integer getInt(String key, int defaultValue);

    BigDecimal getBigDecimal(String key, BigDecimal defaultValue);

    /** 取 JSON 配置；缺失/禁用/解析失败 → 返回默认值（记 warn 日志），不抛异常 */
    <T> T getJson(String key, TypeReference<T> typeRef, T defaultValue);

    List<BillConfig> listAll();

    /** 更新配置值（key 不存在或 value 为空 → 400） */
    void updateConfig(String configKey, String configValue);
}

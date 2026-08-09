package com.uav.billing.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.billing.mapper.BillConfigRepository;
import com.uav.billing.pojo.entity.BillConfig;
import com.uav.billing.service.BillConfigService;
import com.uav.server.enums.ApiErrorCode;
import com.uav.server.exception.BusinessException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

@Service
@Slf4j
public class BillConfigServiceImpl implements BillConfigService {

    @Autowired
    private BillConfigRepository billConfigRepository;

    /** 项目无 ObjectMapper Bean（惯例见 JwtInterceptor），自行创建 */
    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public Double getDouble(String key, double defaultValue) {
        String value = getEnabledValue(key);
        if (value == null) return defaultValue;
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            log.warn("计费配置 {} 数值解析失败: {}，使用默认值 {}", key, value, defaultValue);
            return defaultValue;
        }
    }

    @Override
    public Integer getInt(String key, int defaultValue) {
        String value = getEnabledValue(key);
        if (value == null) return defaultValue;
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            log.warn("计费配置 {} 数值解析失败: {}，使用默认值 {}", key, value, defaultValue);
            return defaultValue;
        }
    }

    @Override
    public BigDecimal getBigDecimal(String key, BigDecimal defaultValue) {
        Double value = getDouble(key, defaultValue.doubleValue());
        return BigDecimal.valueOf(value).setScale(2, java.math.RoundingMode.HALF_UP);
    }

    @Override
    public <T> T getJson(String key, TypeReference<T> typeRef, T defaultValue) {
        String value = getEnabledValue(key);
        if (value == null) return defaultValue;
        try {
            return objectMapper.readValue(value, typeRef);
        } catch (Exception e) {
            log.warn("计费配置 {} JSON 解析失败: {}，使用默认值 {}", key, value, defaultValue);
            return defaultValue;
        }
    }

    @Override
    public List<BillConfig> listAll() {
        return billConfigRepository.findAll();
    }

    @Override
    public void updateConfig(String configKey, String configValue) {
        if (configValue == null || configValue.isBlank()) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_PARAM, "配置值不能为空");
        }
        BillConfig config = billConfigRepository.findByConfigKey(configKey)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, ApiErrorCode.INVALID_PARAM, "配置项不存在: " + configKey));
        config.setConfigValue(configValue.trim());
        billConfigRepository.save(config);
        log.info("计费配置更新: {} = {}", configKey, configValue);
    }

    /** 取启用配置的值；缺失或禁用 → null（调用方回退默认值） */
    private String getEnabledValue(String key) {
        BillConfig config = billConfigRepository.findByConfigKey(key).orElse(null);
        if (config == null || !Boolean.TRUE.equals(config.getEnabled())) {
            log.warn("计费配置 {} 缺失或禁用，使用代码默认值兜底", key);
            return null;
        }
        return config.getConfigValue();
    }
}

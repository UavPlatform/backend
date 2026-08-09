package com.uav.billing.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.uav.billing.mapper.BillConfigRepository;
import com.uav.billing.pojo.entity.BillConfig;
import com.uav.billing.service.BillConfigService;
import com.uav.server.enums.ApiErrorCode;
import com.uav.server.exception.BusinessException;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class BillConfigServiceImpl implements BillConfigService {

    @Autowired
    private BillConfigRepository billConfigRepository;

    /** 项目无 ObjectMapper Bean（惯例见 JwtInterceptor），自行创建 */
    private static final ObjectMapper objectMapper = new ObjectMapper();

    /** 启用配置的本地缓存：计价读走内存，避免每次算价对每个 key 一次 DB 查询 */
    private final Map<String, BillConfig> cache = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        refreshCache();
    }

    /** 全量重载启用配置进缓存；updateConfig 写库后调用，另以定时任务兜底库被直接修改的场景 */
    public void refreshCache() {
        Map<String, BillConfig> fresh = new ConcurrentHashMap<>();
        for (BillConfig config : billConfigRepository.findAll()) {
            if (Boolean.TRUE.equals(config.getEnabled())) {
                fresh.put(config.getConfigKey(), config);
            }
        }
        cache.clear();
        cache.putAll(fresh);
        log.info("计费配置缓存加载完成，共 {} 项", fresh.size());
    }

    /** 兜底：外部直接改库（绕过接口）时最多 1 分钟后生效 */
    @Scheduled(fixedRate = 60000)
    public void scheduledRefresh() {
        refreshCache();
    }

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
    public boolean containsKey(String configKey) {
        return cache.containsKey(configKey);
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
        refreshCache();
        log.info("计费配置更新: {} = {}", configKey, configValue);
    }

    /** 取启用配置的值；缺失或禁用 → null（调用方回退默认值）；读本地缓存，零 DB 查询 */
    private String getEnabledValue(String key) {
        BillConfig config = cache.get(key);
        if (config == null) {
            log.warn("计费配置 {} 缺失或禁用，使用代码默认值兜底", key);
            return null;
        }
        return config.getConfigValue();
    }
}

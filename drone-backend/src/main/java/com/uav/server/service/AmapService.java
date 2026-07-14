package com.uav.server.service;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import com.uav.server.config.AmapConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 高德地图服务端集成
 * <p>
 * 目前提供逆地理编码（坐标 → 地址）。
 * 结果以截断到 4 位小数的坐标对为 key 缓存，避免重复请求高德 API。
 */
@Service
@Slf4j
public class AmapService {

    private static final String REGEO_URL = "https://restapi.amap.com/v3/geocode/regeo";

    @Autowired
    private AmapConfig amapConfig;

    private final RestTemplate restTemplate = new RestTemplate();

    /** 坐标缓存 key = "lng,lat"（各截断到 4 位小数） */
    private final Map<String, String> addressCache = new ConcurrentHashMap<>();

    /**
     * 逆地理编码：经纬度 → 地址描述
     *
     * @param longitude 经度
     * @param latitude  纬度
     * @return 格式化地址，失败时返回 "未知位置"
     */
    public String reverseGeocode(double longitude, double latitude) {
        String cacheKey = truncate(longitude) + "," + truncate(latitude);
        String cached = addressCache.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        try {
            String url = REGEO_URL + "?key=" + amapConfig.getKey()
                    + "&location=" + longitude + "," + latitude
                    + "&extensions=base";
            String resp = restTemplate.getForObject(url, String.class);
            if (resp == null) {
                return "未知位置";
            }

            JSONObject json = JSON.parseObject(resp);
            if (json.getIntValue("status") == 1 && json.containsKey("regeocode")) {
                JSONObject regeo = json.getJSONObject("regeocode");
                String address = regeo.getString("formatted_address");
                if (address != null && !address.isBlank()) {
                    addressCache.put(cacheKey, address);
                    return address;
                }
            }

            log.warn("高德逆地理编码返回异常: lng={}, lat={}, resp={}", longitude, latitude, resp);
            return "未知位置";
        } catch (Exception e) {
            log.error("高德逆地理编码异常: lng={}, lat={}, err={}", longitude, latitude, e.getMessage());
            return "未知位置";
        }
    }

    /**
     * 批量逆地理编码
     *
     * @param points 坐标数组，每个元素为 [lng, lat]
     * @return 地址数组，与输入一一对应
     */
    public String[] reverseGeocodeBatch(double[][] points) {
        if (points == null || points.length == 0) return new String[0];
        String[] results = new String[points.length];
        for (int i = 0; i < points.length; i++) {
            results[i] = reverseGeocode(points[i][0], points[i][1]);
        }
        return results;
    }

    private static double truncate(double value) {
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }
}

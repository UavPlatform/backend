package com.uav.billing.config;

import com.uav.billing.mapper.BillConfigRepository;
import com.uav.billing.pojo.entity.BillConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 启动时幂等种入计费配置默认值：key 不存在才插入，管理端改过的不会被覆盖。
 * 所有商业数值都在这张表里，运营可后台调整，不重启生效。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class BillConfigSeeder implements ApplicationRunner {

    private final BillConfigRepository billConfigRepository;

    /** key → (默认值, 说明)。perKmSteps 不种：留空即回退单档 perKmFee */
    private static final Map<String, String[]> DEFAULTS = new LinkedHashMap<>() {{
        put("baseFee", new String[]{"30", "起步价（元）"});
        put("baseDistanceKm", new String[]{"3", "起步价包含的基础里程（公里）"});
        put("perKmFee", new String[]{"3", "超出基础里程的单价（元/公里），perKmSteps 配置后优先用多档"});
        put("weightSteps", new String[]{"[{\"maxKg\":5,\"fee\":0},{\"maxKg\":15,\"fee\":10},{\"maxKg\":30,\"fee\":25}]", "重量阶梯费率（元）"});
        put("nightRate", new String[]{"0.2", "夜间附加费率（系数，0.2=前三项费用加 20%）"});
        put("nightStart", new String[]{"22", "夜间开始小时（24小时制）"});
        put("nightEnd", new String[]{"6", "夜间结束小时（24小时制）"});
        put("MIN_NEGOTIATED_RATE", new String[]{"0.5", "协商价最低为参考价的倍数"});
        put("WEIGHT_TYPES", new String[]{"[\"TRANSPORT\"]", "按重量计费的任务类型"});
    }};

    @Override
    public void run(ApplicationArguments args) {
        DEFAULTS.forEach((key, entry) -> {
            if (billConfigRepository.findByConfigKey(key).isPresent()) {
                return;
            }
            BillConfig config = new BillConfig();
            config.setConfigKey(key);
            config.setConfigValue(entry[0]);
            config.setDescription(entry[1]);
            config.setEnabled(true);
            billConfigRepository.save(config);
            log.info("初始化计费配置: {} = {}", key, entry[0]);
        });
    }
}

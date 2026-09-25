package com.uav.admin.config;

import com.uav.admin.mapper.AdminRepository;
import com.uav.admin.pojo.entity.Admin;
import com.uav.server.util.PasswordUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 管理员种子账号播种（修复 P1-13：原 data.sql 以明文 '123456' 插入种子管理员，
 * 与登录侧 BCrypt 校验不匹配，/admin/login 必抛 IllegalArgumentException → 500）。
 *
 * <p>启动时编程式播种（幂等）：
 * <ol>
 *   <li>种子账号不存在 → 以 BCrypt 哈希创建；</li>
 *   <li>存在且密码已是 BCrypt 格式（$2 开头）→ 不动，运营改过的密码不受影响；</li>
 *   <li>存在但密码是历史遗留明文 → 原地重置为种子密码的 BCrypt 哈希，让既有部署的管理员可登录。</li>
 * </ol>
 *
 * <p>种子账号/密码可通过 {@code admin.seed.name} / {@code admin.seed.password} 覆盖，
 * 默认 admin / 123456（仅初始化与历史修复用；生产建议通过环境变量改掉弱默认值）。
 */
@Slf4j
@Component
public class AdminSeedInitializer implements ApplicationRunner {

    /** BCrypt 哈希以 $2a$/$2b$/$2y$ 开头（60 字符）；明文历史数据据此识别 */
    static final String BCRYPT_PREFIX = "$2";

    private final AdminRepository adminRepository;

    @Value("${admin.seed.name:admin}")
    private String seedName;

    @Value("${admin.seed.password:123456}")
    private String seedPassword;

    public AdminSeedInitializer(AdminRepository adminRepository) {
        this.adminRepository = adminRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        seed();
    }

    /**
     * 幂等播种；可重复调用（测试用于验证幂等与历史明文修复）。
     */
    public void seed() {
        if (seedName == null || seedName.isBlank()) {
            log.warn("admin.seed.name 未配置，跳过管理员播种");
            return;
        }
        if (seedPassword == null || seedPassword.isBlank()) {
            log.warn("admin.seed.password 未配置，跳过管理员播种");
            return;
        }

        Optional<Admin> existing = adminRepository.findByName(seedName);
        if (existing.isEmpty()) {
            Admin admin = new Admin();
            admin.setName(seedName);
            admin.setPassword(PasswordUtil.hash(seedPassword));
            adminRepository.save(admin);
            log.info("种子管理员已创建（BCrypt 存储）: {}", seedName);
            return;
        }

        Admin admin = existing.get();
        if (admin.getPassword() == null || !admin.getPassword().startsWith(BCRYPT_PREFIX)) {
            // 历史 data.sql 遗留明文：原地修复为 BCrypt，让既有部署的管理员可登录
            admin.setPassword(PasswordUtil.hash(seedPassword));
            adminRepository.save(admin);
            log.warn("管理员 {} 存在历史明文密码，已重置为种子密码（BCrypt 存储）", seedName);
        }
    }
}

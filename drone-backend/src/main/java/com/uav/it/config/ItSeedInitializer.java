package com.uav.it.config;

import com.uav.aircraft.mapper.AircraftModelRepository;
import com.uav.aircraft.pojo.entity.AircraftModel;
import com.uav.equipment.mapper.ProviderDroneRepository;
import com.uav.equipment.pojo.dto.ProviderDroneDTO;
import com.uav.equipment.service.EquipmentCatalogService;
import com.uav.server.util.PasswordUtil;
import com.uav.user.mapper.UserRepository;
import com.uav.user.pojo.entity.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * IT profile 固定种子（仅 {@code spring.profiles.active=it} 时加载）。
 *
 * <p>监管端账号由 {@link com.uav.admin.config.AdminSeedInitializer} 按
 * {@code admin.seed.name/password}（application-it.yml 设为 it-admin / It@123456）播种。
 * 本类幂等播种：飞手 {@code it-pilot}（role=1）、需求方 {@code it-customer}（role=0）、
 * 以及一台可借用的平台无人机（机型取自 Flyway 种子，默认机场 id=1）。
 *
 * <p>永不在 default / dev / prod 下注册；生产误配 {@code it} 亦应拒绝与 prod 同开
 * （见 MockPayGuard / DispatchSandboxPayment 白名单）。
 */
@Slf4j
@Component
@Profile("it")
@Order(100)
public class ItSeedInitializer implements ApplicationRunner {

    public static final int ROLE_CUSTOMER = 0;
    public static final int ROLE_PILOT = 1;

    private final UserRepository users;
    private final AircraftModelRepository models;
    private final ProviderDroneRepository drones;
    private final EquipmentCatalogService catalog;

    @Value("${it.seed.password:It@123456}")
    private String password;

    @Value("${it.seed.pilot-username:it-pilot}")
    private String pilotUsername;

    @Value("${it.seed.customer-username:it-customer}")
    private String customerUsername;

    @Value("${it.seed.drone-serial:IT-DRONE-001}")
    private String droneSerial;

    @Value("${it.seed.aircraft-model-code:FC30}")
    private String aircraftModelCode;

    public ItSeedInitializer(UserRepository users, AircraftModelRepository models,
                             ProviderDroneRepository drones, EquipmentCatalogService catalog) {
        this.users = users;
        this.models = models;
        this.drones = drones;
        this.catalog = catalog;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        seed();
    }

    /** 幂等播种；可重复调用（测试用于验证）。 */
    public void seed() {
        ensureUser(customerUsername, ROLE_CUSTOMER);
        ensureUser(pilotUsername, ROLE_PILOT);
        ensureDrone();
    }

    private void ensureUser(String userName, int role) {
        if (users.existsByUserName(userName)) {
            User existing = users.findByUserName(userName).orElseThrow();
            if (existing.getRole() == null || existing.getRole() != role) {
                existing.setRole(role);
                users.save(existing);
                log.warn("IT 种子用户 {} 角色已纠正为 {}", userName, role);
            }
            return;
        }
        User user = new User();
        user.setUserName(userName);
        user.setPassword(PasswordUtil.hash(password));
        user.setStatus(1);
        user.setRole(role);
        users.save(user);
        log.info("IT 种子用户已创建: {} (role={})", userName, role);
    }

    private void ensureDrone() {
        if (drones.existsBySerialNumber(droneSerial)) {
            return;
        }
        AircraftModel model = models.findByModelCode(aircraftModelCode)
                .orElseThrow(() -> new IllegalStateException(
                        "IT 种子需要机型 " + aircraftModelCode + "（应由 Flyway V2 播种）"));
        if (!Boolean.TRUE.equals(model.getEnabled()) || !Boolean.TRUE.equals(model.getTransportEnabled())) {
            throw new IllegalStateException("IT 种子机型须启用且可吊运: " + aircraftModelCode);
        }
        catalog.register(new ProviderDroneDTO(model.getId(), droneSerial));
        log.info("IT 种子无人机已登记: serial={} model={}", droneSerial, aircraftModelCode);
    }
}

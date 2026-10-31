package com.uav.it;

import com.uav.admin.mapper.AdminRepository;
import com.uav.equipment.mapper.ProviderDroneRepository;
import com.uav.it.config.ItSeedInitializer;
import com.uav.support.TestAccounts;
import com.uav.user.mapper.UserRepository;
import com.uav.user.pojo.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 显式激活 it profile：固定种子账号可登录，参考无人机已登记；
 * 使用 H2（与 test 同口径）以免依赖外部 MySQL。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("it")
@Transactional
@TestPropertySource(properties = {
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.url=jdbc:h2:mem:drone_it_seed;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;NON_KEYWORDS=USER;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.show-sql=false",
        "spring.sql.init.mode=never",
        "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration",
        "jwt.secret=test-jwt-secret-0123456789abcdef0123456789abcdef",
        "jwt.expiration=3600000",
        "jwt.refresh-expiration=604800000",
        "trtc.sdk-app-id=1400000000",
        "trtc.secret-key=test-trtc-secret-key-0123456789abcdef",
        "trtc.room-expire=86400",
        "amap.key=amap-test-key",
        "amap.security-key=amap-test-security-key",
        "amap.service-host=https://test.example/_AMapService"
})
class ItSeedProfileIT {

    private static final String IT_PASSWORD = "It@123456";

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ItSeedInitializer itSeedInitializer;

    @Autowired
    UserRepository users;

    @Autowired
    AdminRepository admins;

    @Autowired
    ProviderDroneRepository drones;

    @Test
    @DisplayName("it profile 下种子管理员 it-admin 可登录")
    void itAdminLogin() throws Exception {
        assertThat(admins.findByName("it-admin")).isPresent();
        mockMvc.perform(post("/admin/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"it-admin\",\"password\":\"" + IT_PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andExpect(jsonPath("$.data.admin.name").value("it-admin"));
    }

    @Test
    @DisplayName("it profile 下 it-customer / it-pilot 可登录且角色正确")
    void itUserAccountsLogin() throws Exception {
        User customer = users.findByUserName("it-customer").orElseThrow();
        User pilot = users.findByUserName("it-pilot").orElseThrow();
        assertThat(customer.getRole()).isEqualTo(ItSeedInitializer.ROLE_CUSTOMER);
        assertThat(pilot.getRole()).isEqualTo(ItSeedInitializer.ROLE_PILOT);

        mockMvc.perform(post("/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userName\":\"it-customer\",\"password\":\"" + IT_PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").isNotEmpty());

        mockMvc.perform(post("/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userName\":\"it-pilot\",\"password\":\"" + IT_PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").isNotEmpty());

        // 与 TestAccounts 解析口径一致：token 内 role 匹配
        var accounts = TestAccounts.overMockMvc(mockMvc);
        var customerAccount = accounts.login("it-customer", IT_PASSWORD);
        var pilotAccount = accounts.login("it-pilot", IT_PASSWORD);
        assertThat(customerAccount.role()).isEqualTo(0);
        assertThat(pilotAccount.role()).isEqualTo(1);
    }

    @Test
    @DisplayName("it profile 下参考无人机 IT-DRONE-001 已登记；seed 幂等")
    void itDroneSeededIdempotently() {
        assertThat(drones.existsBySerialNumber("IT-DRONE-001")).isTrue();
        long before = drones.count();
        itSeedInitializer.seed();
        itSeedInitializer.seed();
        assertThat(drones.count()).isEqualTo(before);
        assertThat(users.count()).isGreaterThanOrEqualTo(2);
    }
}

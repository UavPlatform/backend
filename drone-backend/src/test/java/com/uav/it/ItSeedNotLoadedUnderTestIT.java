package com.uav.it;

import com.uav.it.config.ItSeedInitializer;
import com.uav.support.IntegrationTestBase;
import com.uav.user.mapper.UserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 证明 ItSeedInitializer 不会在默认 test profile 下加载，
 * 因而 it-* 固定账号不会污染普通集成测试库。
 */
class ItSeedNotLoadedUnderTestIT extends IntegrationTestBase {

    @Autowired
    ApplicationContext context;

    @Autowired
    UserRepository users;

    @Test
    @DisplayName("test profile 下不存在 ItSeedInitializer bean")
    void itSeedBeanAbsentUnderTestProfile() {
        assertThat(context.getBeanNamesForType(ItSeedInitializer.class)).isEmpty();
    }

    @Test
    @DisplayName("test profile 下不存在 it-pilot / it-customer 种子用户")
    void itAccountsAbsentUnderTestProfile() {
        assertThat(users.existsByUserName("it-pilot")).isFalse();
        assertThat(users.existsByUserName("it-customer")).isFalse();
    }
}

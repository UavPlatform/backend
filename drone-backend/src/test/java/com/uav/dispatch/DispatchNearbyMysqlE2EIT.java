package com.uav.dispatch;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import static org.assertj.core.api.Assertions.assertThat;

/** Reuses the real HTTP/concurrency scenario against an explicitly supplied isolated MySQL 8 instance. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.driver-class-name=com.mysql.cj.jdbc.Driver",
        "spring.datasource.url=jdbc:mysql://${T4_DB_HOST}:${T4_DB_PORT}/${T4_DB_NAME}?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai",
        "spring.datasource.username=${T4_DB_USER}", "spring.datasource.password=${T4_DB_PASSWORD}",
        "spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.show-sql=false", "spring.flyway.enabled=true",
        "spring.flyway.locations=classpath:db/migration", "spring.sql.init.mode=never"
})
@EnabledIfEnvironmentVariable(named = "T4_DB_HOST", matches = ".+")
@EnabledIfEnvironmentVariable(named = "T4_DB_PORT", matches = ".+")
@EnabledIfEnvironmentVariable(named = "T4_DB_NAME", matches = ".+")
@EnabledIfEnvironmentVariable(named = "T4_DB_USER", matches = ".+")
@EnabledIfEnvironmentVariable(named = "T4_DB_PASSWORD", matches = ".+")
class DispatchNearbyMysqlE2EIT extends DispatchNearbyConcurrencyE2EIT {
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;

    @BeforeAll static void refuseOperatorDatabase() {
        int port = Integer.parseInt(System.getenv("T4_DB_PORT").trim());
        assertThat(port).as("Only an explicit isolated database; never operator port 3306").isBetween(1, 65535).isNotEqualTo(3306);
        assertThat(System.getenv("T4_DB_NAME")).as("Never the operator database").isNotEqualTo("drone_db");
    }
    @Test void mysql8ActuallyAppliedAllDispatchMigrationsAndValidatesNewColumns() throws Exception {
        try (var connection = dataSource.getConnection()) {
            var metadata = connection.getMetaData();
            assertThat(metadata.getDatabaseProductName()).containsIgnoringCase("mysql");
            assertThat(metadata.getDatabaseMajorVersion()).as("Target environment is MySQL 8, not H2 or local MySQL 9").isEqualTo(8);
            assertThat(connection.getCatalog()).isEqualTo(System.getenv("T4_DB_NAME"));
        }
        assertThat(jdbc.queryForList("SELECT version FROM flyway_schema_history WHERE success = TRUE", String.class))
                .contains("10", "11", "12", "13", "14", "15", "16", "17", "18", "19");
        assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'dispatch_pilot_position'", String.class))
                .contains("session_key", "captured_at", "expires_at");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() AND table_name = 'dispatch_order' AND column_name = 'nearby_waiting_since'", Integer.class)).isEqualTo(1);
    }
}

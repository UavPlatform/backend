package com.uav.flight.service;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-0009 on real MySQL 8: V20/V21 convert an existing sample table in place (rows seeded before the migration
 * survive), the retention job creates future monthly partitions and drops partitions past 180 days.
 * Needs Docker (CI runners have it); skipped on machines without Docker.
 */
@Testcontainers(disabledWithoutDocker = true)
class TelemetryPartitionMysqlE2EIT {
    private static final long DAY = 86_400_000L;

    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");

    private Flyway flyway(String target) {
        return Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration").target(target).load();
    }

    @Test
    void migrationKeepsExistingRowsAndRetentionRollsPartitions() throws Exception {
        flyway("19").migrate();
        long now = System.currentTimeMillis();
        long old = now - 250 * DAY, recent = now - 2 * DAY;
        try (var c = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             var s = c.createStatement()) {
            s.execute("SET FOREIGN_KEY_CHECKS = 0");
            s.execute("INSERT INTO dispatch_flight_connection (id, order_id, pilot_id, assignment_version, request_key, serial_number,"
                    + " model_code, model_name, opened_at) VALUES ('c1', 'o1', 1, 1, 'k1', 'SN-1', 'FC30', 'DJI FlyCart 30', NOW(6))");
            long[] at = {0L, old, recent};
            for (int i = 0; i < at.length; i++) {
                s.execute("INSERT INTO dispatch_flight_sample (connection_id, order_id, sequence_number, fingerprint, captured_at,"
                        + " received_at, latitude) VALUES ('c1', 'o1', " + i + ", 'f" + i + "', " + at[i] + ", NOW(6), 28.2)");
            }
        }

        flyway("latest").migrate();
        var jdbc = new JdbcTemplate(new SingleConnectionDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword(), true));
        // Existing data months get their own partition up to BACKFILL_MONTHS back; older rows (1970, ~250 days) sit in p_hist.
        YearMonth current = month(now);
        assertThat(partitions(jdbc)).startsWith("p_hist", TelemetryPartitions.name(current.minusMonths(TelemetryPartitions.BACKFILL_MONTHS)))
                .contains(TelemetryPartitions.name(month(recent)), TelemetryPartitions.name(current.plusMonths(2)))
                .endsWith(TelemetryPartitions.MAX);
        assertThat(jdbc.queryForList("SELECT captured_at FROM dispatch_flight_sample WHERE serial_number = 'SN-1' ORDER BY captured_at",
                Long.class)).containsExactly(0L, old, recent);
        // Keys widened with the partition column; device dedupe key still enforced.
        assertThat(jdbc.update("INSERT IGNORE INTO dispatch_flight_sample (connection_id, order_id, serial_number, sequence_number,"
                + " fingerprint, captured_at, received_at) VALUES ('c1', 'o1', 'SN-1', 99, 'x', ?, NOW(6))", recent)).isZero();

        var outcome = new TelemetryRetentionJob(jdbc, Clock.systemUTC(), 180, 2).maintain();
        assertThat(outcome.dropped()).contains("p_hist", TelemetryPartitions.name(current.minusMonths(TelemetryPartitions.BACKFILL_MONTHS)));
        assertThat(partitions(jdbc)).doesNotContain("p_hist").contains(TelemetryPartitions.name(month(recent)));
        assertThat(jdbc.queryForList("SELECT captured_at FROM dispatch_flight_sample", Long.class)).containsExactly(recent);

        var later = Clock.fixed(Instant.ofEpochMilli(now).plus(Duration.ofDays(95)), TelemetryPartitions.ZONE);
        var rolled = new TelemetryRetentionJob(jdbc, later, 180, 2).maintain();
        YearMonth future = month(later.millis()).plusMonths(2);
        assertThat(rolled.added()).contains(TelemetryPartitions.name(future));
        assertThat(partitions(jdbc)).contains(TelemetryPartitions.name(future)).endsWith(TelemetryPartitions.MAX);
        long ahead = later.millis() + 40 * DAY;
        jdbc.update("INSERT INTO dispatch_flight_sample (connection_id, order_id, serial_number, sequence_number, fingerprint,"
                + " captured_at, received_at) VALUES ('c1', 'o1', 'SN-1', 100, 'y', ?, NOW(6))", ahead);
        assertThat(jdbc.queryForObject("SELECT partition_name FROM information_schema.partitions WHERE table_schema = DATABASE()"
                + " AND table_name = 'dispatch_flight_sample' AND partition_name = ? AND table_rows >= 0",
                String.class, TelemetryPartitions.name(month(ahead)))).isNotNull();
        assertThat(jdbc.queryForList("SELECT captured_at FROM dispatch_flight_sample ORDER BY captured_at", Long.class))
                .containsExactly(recent, ahead);
    }

    private static YearMonth month(long millis) {
        return YearMonth.from(Instant.ofEpochMilli(millis).atZone(TelemetryPartitions.ZONE));
    }

    private static List<String> partitions(JdbcTemplate jdbc) {
        return jdbc.queryForList("SELECT partition_name FROM information_schema.partitions WHERE table_schema = DATABASE()"
                + " AND table_name = 'dispatch_flight_sample' ORDER BY partition_ordinal_position", String.class);
    }
}

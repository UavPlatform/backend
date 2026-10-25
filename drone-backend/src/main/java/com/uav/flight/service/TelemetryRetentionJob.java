package com.uav.flight.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/**
 * ADR-0009 retention: telemetry samples older than {@code flight.telemetry.retention-days} (default 180)
 * are removed by dropping whole monthly partitions on MySQL; future partitions are created ahead of time.
 * Billing facts (connection records, executed drone, flight seconds, final amount) are not in this table
 * and are never removed here.
 */
@Slf4j
@Component
public class TelemetryRetentionJob {
    private static final long DAY_MS = 86_400_000L;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final int retentionDays;
    private final int monthsAhead;

    @Autowired
    public TelemetryRetentionJob(JdbcTemplate jdbc,
            @Value("${flight.telemetry.retention-days:180}") int retentionDays,
            @Value("${flight.telemetry.partition-months-ahead:2}") int monthsAhead) {
        this(jdbc, Clock.system(TelemetryPartitions.ZONE), retentionDays, monthsAhead);
    }

    TelemetryRetentionJob(JdbcTemplate jdbc, Clock clock, int retentionDays, int monthsAhead) {
        if (retentionDays < 1 || monthsAhead < 1) throw new IllegalArgumentException("retention-days and partition-months-ahead must be >= 1");
        this.jdbc = jdbc; this.clock = clock; this.retentionDays = retentionDays; this.monthsAhead = monthsAhead;
    }

    public record Outcome(List<String> added, List<String> dropped, int deletedRows) { }

    @Scheduled(cron = "${flight.telemetry.retention-cron:0 30 3 * * *}", zone = "Asia/Shanghai")
    public void scheduled() {
        try {
            var outcome = maintain();
            log.info("遥测保留维护完成: added={}, dropped={}, deletedRows={}", outcome.added(), outcome.dropped(), outcome.deletedRows());
        } catch (RuntimeException e) {
            log.error("遥测保留维护失败，将于下次调度重试", e);
        }
    }

    public Outcome maintain() {
        long cutoff = clock.millis() - retentionDays * DAY_MS;
        boolean mysql = Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>) c ->
                TelemetryPartitions.isMysql(c.getMetaData().getDatabaseProductName())));
        List<java.util.Map<String, Object>> partitions = mysql ? jdbc.queryForList(
                "SELECT partition_name AS name, partition_description AS bound FROM information_schema.partitions "
                        + "WHERE table_schema = DATABASE() AND table_name = ? AND partition_name IS NOT NULL "
                        + "ORDER BY partition_ordinal_position", TelemetryPartitions.TABLE) : List.of();
        if (partitions.isEmpty()) {
            int rows = jdbc.update("DELETE FROM " + TelemetryPartitions.TABLE + " WHERE captured_at < ?", cutoff);
            return new Outcome(List.of(), List.of(), rows);
        }
        long highest = Long.MIN_VALUE;
        var expired = new ArrayList<String>();
        for (var p : partitions) {
            String name = String.valueOf(p.get("name"));
            if (TelemetryPartitions.MAX.equals(name)) continue;
            long bound = Long.parseLong(String.valueOf(p.get("bound")).trim());
            highest = Math.max(highest, bound);
            if (bound <= cutoff) expired.add(name);
        }
        var added = new ArrayList<String>();
        YearMonth current = YearMonth.from(Instant.ofEpochMilli(clock.millis()).atZone(TelemetryPartitions.ZONE));
        for (int i = 0; i <= monthsAhead; i++) {
            YearMonth m = current.plusMonths(i);
            if (TelemetryPartitions.upperBound(m) > highest) {
                jdbc.execute(TelemetryPartitions.addStatement(m));
                added.add(TelemetryPartitions.name(m));
            }
        }
        if (!expired.isEmpty()) jdbc.execute(TelemetryPartitions.dropStatement(expired));
        return new Outcome(added, expired, 0);
    }
}

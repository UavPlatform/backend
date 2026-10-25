package com.uav.flight.service;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.Clock;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** ADR-0009：遥测按月分区、180 天保留（MySQL 删分区；其他库按行删除）。 */
class TelemetryRetentionJobTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    // 2026-10-25 10:00 Asia/Shanghai
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-25T02:00:00Z"), TelemetryPartitions.ZONE);

    private void database(boolean mysql) {
        when(jdbc.execute(any(ConnectionCallback.class))).thenReturn(mysql);
    }

    @Test void partitionNamesAndBoundariesFollowShanghaiMonths() {
        var oct = YearMonth.of(2026, 10);
        assertThat(TelemetryPartitions.name(oct)).isEqualTo("p202610");
        assertThat(TelemetryPartitions.upperBound(oct)).isEqualTo(Instant.parse("2026-10-31T16:00:00Z").toEpochMilli());
        var sql = TelemetryPartitions.convertStatements(oct, 2, null);
        assertThat(sql.get(0)).contains("PRIMARY KEY (id, captured_at)").contains("uk_flight_sample (connection_id, sequence_number, captured_at)");
        assertThat(sql.get(1)).contains("p_hist", "p202610", "p202611", "p202612", "pmax VALUES LESS THAN MAXVALUE").doesNotContain("p202701");
        assertThat(TelemetryPartitions.convertStatements(oct, 2, YearMonth.of(2026, 8)).get(1))
                .contains("PARTITION p_hist VALUES LESS THAN (" + TelemetryPartitions.upperBound(YearMonth.of(2026, 7)) + ")", "p202608", "p202609")
                .doesNotContain("p202607 ");
        assertThat(TelemetryPartitions.convertStatements(oct, 2, YearMonth.of(2020, 1)).get(1)).contains("p202603").doesNotContain("p202602");
        assertThat(TelemetryPartitions.addStatement(oct)).contains("REORGANIZE PARTITION pmax INTO (PARTITION p202610");
        assertThat(TelemetryPartitions.dropStatement(List.of("p_hist", "p202603"))).endsWith("DROP PARTITION p_hist, p202603");
        assertThat(TelemetryPartitions.isMysql("MySQL")).isTrue();
        assertThat(TelemetryPartitions.isMysql("H2")).isFalse();
        assertThat(TelemetryPartitions.isMysql(null)).isFalse();
    }

    @Test void nonMysqlDeletesRowsOlderThanRetention() {
        database(false);
        when(jdbc.update(anyString(), anyLong())).thenReturn(3);
        var outcome = new TelemetryRetentionJob(jdbc, clock, 180, 2).maintain();
        assertThat(outcome.deletedRows()).isEqualTo(3);
        verify(jdbc).update(contains("DELETE FROM dispatch_flight_sample"), eq(clock.millis() - 180L * 86_400_000L));
    }

    @Test void mysqlDropsExpiredMonthsAndCreatesUpcomingOnes() {
        database(true);
        long cutoff = clock.millis() - 180L * 86_400_000L;
        when(jdbc.queryForList(anyString(), eq(TelemetryPartitions.TABLE))).thenReturn(List.of(
                Map.of("name", "p_hist", "bound", String.valueOf(TelemetryPartitions.upperBound(YearMonth.of(2026, 3)))),
                Map.of("name", "p202604", "bound", String.valueOf(TelemetryPartitions.upperBound(YearMonth.of(2026, 4)))),
                Map.of("name", "p202610", "bound", String.valueOf(TelemetryPartitions.upperBound(YearMonth.of(2026, 10)))),
                Map.of("name", "pmax", "bound", "MAXVALUE")));
        var outcome = new TelemetryRetentionJob(jdbc, clock, 180, 2).maintain();
        // cutoff = 2026-04-28: March data is wholly expired; April still holds retained days, so it stays.
        assertThat(TelemetryPartitions.upperBound(YearMonth.of(2026, 3))).isLessThanOrEqualTo(cutoff);
        assertThat(TelemetryPartitions.upperBound(YearMonth.of(2026, 4))).isGreaterThan(cutoff);
        assertThat(outcome.dropped()).containsExactly("p_hist");
        assertThat(outcome.added()).containsExactly("p202611", "p202612");
        verify(jdbc).execute(TelemetryPartitions.addStatement(YearMonth.of(2026, 11)));
        verify(jdbc).execute(TelemetryPartitions.dropStatement(List.of("p_hist")));
        verify(jdbc, never()).update(anyString(), anyLong());
    }

    @Test void mysqlWithoutPartitionsFallsBackToDeletesAndScheduledRunSwallowsErrors() {
        database(true);
        when(jdbc.queryForList(anyString(), eq(TelemetryPartitions.TABLE))).thenReturn(List.of());
        var job = new TelemetryRetentionJob(jdbc, clock, 180, 2);
        job.scheduled();
        verify(jdbc).update(contains("DELETE"), anyLong());
        when(jdbc.execute(any(ConnectionCallback.class))).thenThrow(new IllegalStateException("db down"));
        assertThatCode(job::scheduled).doesNotThrowAnyException();
        assertThatThrownBy(() -> new TelemetryRetentionJob(jdbc, clock, 0, 2)).isInstanceOf(IllegalArgumentException.class);
    }
}

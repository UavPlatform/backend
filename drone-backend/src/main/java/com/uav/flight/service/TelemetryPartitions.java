package com.uav.flight.service;

import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Monthly RANGE partitions of {@code dispatch_flight_sample} by {@code captured_at} (epoch millis), ADR-0009.
 * Month boundaries use Asia/Shanghai. Only MySQL is partitioned; other databases (H2 tests) use row deletes.
 */
public final class TelemetryPartitions {
    public static final String TABLE = "dispatch_flight_sample";
    public static final String MAX = "pmax";
    public static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter NAME = DateTimeFormatter.ofPattern("yyyyMM");

    private TelemetryPartitions() { }

    public static boolean isMysql(String product) {
        return product != null && product.toLowerCase(Locale.ROOT).contains("mysql");
    }

    /** Exclusive upper bound of month {@code m}: first millisecond of the next month. */
    public static long upperBound(YearMonth m) {
        return m.plusMonths(1).atDay(1).atStartOfDay(ZONE).toInstant().toEpochMilli();
    }

    public static String name(YearMonth m) {
        return "p" + m.format(NAME);
    }

    /** One-off conversion: keys must contain the partition column, then RANGE partitions + MAXVALUE catch-all. */
    public static List<String> convertStatements(YearMonth current, int monthsAhead) {
        var sql = new ArrayList<String>();
        sql.add("ALTER TABLE " + TABLE + " DROP INDEX uk_flight_sample, "
                + "ADD UNIQUE KEY uk_flight_sample (connection_id, sequence_number, captured_at), "
                + "DROP PRIMARY KEY, ADD PRIMARY KEY (id, captured_at)");
        var parts = new StringBuilder("ALTER TABLE " + TABLE + " PARTITION BY RANGE (captured_at) (");
        parts.append("PARTITION p_hist VALUES LESS THAN (").append(upperBound(current.minusMonths(1))).append("), ");
        for (int i = 0; i <= monthsAhead; i++) {
            YearMonth m = current.plusMonths(i);
            parts.append("PARTITION ").append(name(m)).append(" VALUES LESS THAN (").append(upperBound(m)).append("), ");
        }
        parts.append("PARTITION ").append(MAX).append(" VALUES LESS THAN MAXVALUE)");
        sql.add(parts.toString());
        return sql;
    }

    /** Splits the MAXVALUE partition so month {@code m} gets its own partition. */
    public static String addStatement(YearMonth m) {
        return "ALTER TABLE " + TABLE + " REORGANIZE PARTITION " + MAX + " INTO (PARTITION " + name(m)
                + " VALUES LESS THAN (" + upperBound(m) + "), PARTITION " + MAX + " VALUES LESS THAN MAXVALUE)";
    }

    public static String dropStatement(List<String> partitions) {
        return "ALTER TABLE " + TABLE + " DROP PARTITION " + String.join(", ", partitions);
    }
}

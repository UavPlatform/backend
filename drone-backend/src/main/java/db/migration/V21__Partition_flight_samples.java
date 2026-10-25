package db.migration;

import com.uav.flight.service.TelemetryPartitions;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.time.YearMonth;

/**
 * ADR-0009: converts flight samples to monthly RANGE partitions on MySQL so retention drops whole
 * partitions. Existing production rows stay in place (ALTER TABLE ... PARTITION BY, no new table/copy/rename).
 * Other databases (H2 in tests) keep a plain table; retention there deletes rows.
 */
public class V21__Partition_flight_samples extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        var connection = context.getConnection();
        if (!TelemetryPartitions.isMysql(connection.getMetaData().getDatabaseProductName())) return;
        try (var statement = connection.createStatement()) {
            YearMonth oldest = null;
            try (var rs = statement.executeQuery("SELECT MIN(captured_at) FROM " + TelemetryPartitions.TABLE)) {
                if (rs.next() && rs.getObject(1) != null) {
                    oldest = YearMonth.from(java.time.Instant.ofEpochMilli(rs.getLong(1)).atZone(TelemetryPartitions.ZONE));
                }
            }
            for (String sql : TelemetryPartitions.convertStatements(YearMonth.now(TelemetryPartitions.ZONE), 2, oldest)) {
                statement.execute(sql);
            }
        }
    }
}

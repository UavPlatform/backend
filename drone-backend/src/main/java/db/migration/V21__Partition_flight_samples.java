package db.migration;

import com.uav.flight.service.TelemetryPartitions;
import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.time.YearMonth;

/**
 * ADR-0009: converts flight samples to monthly RANGE partitions on MySQL so retention drops whole
 * partitions. Other databases (H2 in tests) keep a plain table; retention there deletes rows.
 */
public class V21__Partition_flight_samples extends BaseJavaMigration {
    @Override
    public void migrate(Context context) throws Exception {
        var connection = context.getConnection();
        if (!TelemetryPartitions.isMysql(connection.getMetaData().getDatabaseProductName())) return;
        try (var statement = connection.createStatement()) {
            for (String sql : TelemetryPartitions.convertStatements(YearMonth.now(TelemetryPartitions.ZONE), 2)) {
                statement.execute(sql);
            }
        }
    }
}

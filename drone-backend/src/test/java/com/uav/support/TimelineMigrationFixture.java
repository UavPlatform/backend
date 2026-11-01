package com.uav.support;

import org.flywaydb.core.Flyway;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDateTime;
import static org.assertj.core.api.Assertions.assertThat;

/** ADR-0013: the same real migration and historical cases run on H2 and isolated MySQL. */
public final class TimelineMigrationFixture {
    private TimelineMigrationFixture() { }
    public static void verify(String url, String user) throws Exception {
        verify(url, user, "");
    }
    public static void verify(String url, String user, String password) throws Exception {
        try (var connection = DriverManager.getConnection(url, user, password)) {
            try (var sql = connection.createStatement()) {
                // Post-V24 dispatch shape (timeline columns already present; values pre-backfilled).
                sql.execute("CREATE TABLE dispatch_order (id VARCHAR(36) PRIMARY KEY, user_id BIGINT, assignment_version BIGINT, "
                        + "assigned_pilot_id BIGINT, payment_reference VARCHAR(64), created_at TIMESTAMP(6), updated_at TIMESTAMP(6), nearby_waiting_since TIMESTAMP(6), "
                        + "deposit_paid_at TIMESTAMP(6), waiting_since TIMESTAMP(6), accepted_at TIMESTAMP(6), arrived_at TIMESTAMP(6))");
                sql.execute("CREATE TABLE dispatch_assignment (order_id VARCHAR(36), assignment_version BIGINT, pilot_id BIGINT, "
                        + "accepted_at TIMESTAMP(6), arrived_at TIMESTAMP(6), ended_at TIMESTAMP(6), end_reason VARCHAR(32))");
                sql.execute("CREATE TABLE dispatch_notification (order_id VARCHAR(36), recipient_id BIGINT, audience VARCHAR(16), kind VARCHAR(32), created_at TIMESTAMP(6))");
                sql.execute("INSERT INTO dispatch_order VALUES "
                        + "('first',1,1,7,'paid','2026-10-24 10:00:00','2026-10-24 20:00:00','2026-10-24 20:00:00',"
                        + "'2026-10-24 11:00:00','2026-10-24 11:00:00','2026-10-24 12:00:00','2026-10-24 13:00:00'),"
                        + "('second',1,2,8,'paid','2026-10-24 10:00:00','2026-10-24 20:00:00','2026-10-24 20:00:00',"
                        + "'2026-10-24 11:00:00','2026-10-24 14:00:00','2026-10-24 15:00:00',NULL),"
                        + "('released',1,1,NULL,'paid','2026-10-24 10:00:00','2026-10-24 20:00:00','2026-10-24 20:00:00',"
                        + "'2026-10-24 11:00:00','2026-10-24 14:00:00',NULL,NULL),"
                        + "('unknown',1,0,NULL,'paid','2026-10-24 10:00:00','2026-10-24 20:00:00','2026-10-24 20:00:00',"
                        + "NULL,NULL,NULL,NULL)");
                sql.execute("INSERT INTO dispatch_notification VALUES "
                        + "('first',1,'OWNER','DEPOSIT_PAID','2026-10-24 11:00:00'),"
                        + "('second',1,'OWNER','DEPOSIT_PAID','2026-10-24 11:00:00'),"
                        + "('released',1,'OWNER','DEPOSIT_PAID','2026-10-24 11:00:00'),"
                        + "('unknown',99,'OWNER','DEPOSIT_PAID','2026-10-24 09:00:00')");
                sql.execute("INSERT INTO dispatch_assignment VALUES "
                        + "('first',1,7,'2026-10-24 12:00:00','2026-10-24 13:00:00',NULL,NULL),"
                        + "('second',1,7,'2026-10-24 12:00:00','2026-10-24 13:00:00','2026-10-24 14:00:00','PILOT_RELEASED'),"
                        + "('second',2,8,'2026-10-24 15:00:00',NULL,NULL,NULL),"
                        + "('released',1,7,'2026-10-24 12:00:00','2026-10-24 13:00:00','2026-10-24 14:00:00','PILOT_RELEASED')");
                // Pre-V25 equipment stubs retained for local schema; baseline 25 → only V26 runs.
                sql.execute("CREATE TABLE aircraft_model (id BIGINT PRIMARY KEY)");
                sql.execute("CREATE TABLE provider_drone (id VARCHAR(36) PRIMARY KEY, aircraft_model_id BIGINT NOT NULL)");
                sql.execute("CREATE TABLE drone_borrow (id VARCHAR(36) PRIMARY KEY, drone_id VARCHAR(36) NOT NULL, "
                        + "pilot_id BIGINT NOT NULL, request_key VARCHAR(64) NOT NULL, borrowed_at TIMESTAMP(6) NOT NULL, returned_at TIMESTAMP(6))");
            }
            var result = Flyway.configure().dataSource(url, user, password).locations("filesystem:src/main/resources/db/migration")
                    .baselineOnMigrate(true).baselineVersion("25").load().migrate();
            assertThat(result.migrationsExecuted).isEqualTo(1);
            assertTime(connection, "first", "deposit_paid_at", "2026-10-24T11:00:00");
            assertTime(connection, "first", "waiting_since", "2026-10-24T11:00:00");
            assertTime(connection, "first", "accepted_at", "2026-10-24T12:00:00");
            assertTime(connection, "first", "arrived_at", "2026-10-24T13:00:00");
            assertTime(connection, "second", "waiting_since", "2026-10-24T14:00:00");
            assertTime(connection, "second", "accepted_at", "2026-10-24T15:00:00");
            assertTime(connection, "second", "arrived_at", null);
            assertTime(connection, "released", "waiting_since", "2026-10-24T14:00:00");
            assertTime(connection, "released", "accepted_at", null);
            assertTime(connection, "released", "arrived_at", null);
            for (var field : new String[]{"deposit_paid_at", "waiting_since", "accepted_at", "arrived_at"})
                assertTime(connection, "unknown", field, null);
            assertTime(connection, "first", "created_at", "2026-10-24T10:00:00");
            assertTime(connection, "first", "updated_at", "2026-10-24T20:00:00");
        }
    }
    private static void assertTime(Connection connection, String id, String column, String expected) throws Exception {
        try (var query = connection.prepareStatement("SELECT " + column + " FROM dispatch_order WHERE id = ?")) {
            query.setString(1, id);
            try (var result = query.executeQuery()) {
                assertThat(result.next()).isTrue();
                assertThat(result.getObject(1, LocalDateTime.class)).isEqualTo(expected == null ? null : LocalDateTime.parse(expected));
            }
        }
    }
}

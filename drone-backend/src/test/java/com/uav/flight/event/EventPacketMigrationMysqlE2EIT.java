package com.uav.flight.event;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Empty-database init and an upgrade from current main (V26) on disposable MySQL. Old checksums stay put. */
class EventPacketMigrationMysqlE2EIT {
    private static final List<String> EVENT_TABLES = List.of(
            "flight_event_chain", "flight_event_packet", "flight_event_receipt",
            "flight_event_result", "flight_event_fact", "flight_event_conflict");

    @Test
    void emptyDatabaseInitializesAtVersion27() throws Exception {
        var db = EventMysql.empty();
        EventMysql.wipe(db);
        var flyway = flyway(db, null);
        assertThat(flyway.migrate().success).isTrue();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("27");
        assertThat(flyway.info().pending()).isEmpty();
        assertThat(flyway(db, null).migrate().success).isTrue();
        try (Connection connection = connect(db)) {
            assertMysql(connection);
            var installed = history(connection);
            assertThat(installed).containsKeys("1", "21", "26", "27");
            assertSqlChecksums(installed);
            assertThat(installed.get("21")).isEqualTo(new Installed("21", "JDBC", null, 1));
            assertThat(installed.get("27")).isEqualTo(new Installed("27", "JDBC", null, 1));
            for (String table : EVENT_TABLES) assertThat(tableCount(connection, table)).isEqualTo(1);
            assertThat(tableCount(connection, "dispatch_flight_sample")).isEqualTo(1);
            assertColumn(connection);
        }
    }

    @Test
    void upgradeFromVersion26KeepsChecksumsSamplesAndDoesNotCleanEvents() throws Exception {
        var db = EventMysql.upgrade();
        EventMysql.wipe(db);
        assertThat(flyway(db, "26").migrate().success).isTrue();
        Map<String, Installed> before;
        try (Connection connection = connect(db)) {
            before = history(connection);
            assertThat(before).containsKey("26").doesNotContainKey("27");
            assertSqlChecksums(before);
            assertThat(before.get("21").checksum()).isNull();
            assertThat(before.get("21").type()).isEqualTo("JDBC");
            try (Statement statement = connection.createStatement()) {
                statement.execute("INSERT INTO dispatch_flight_sample (connection_id, order_id, serial_number, sequence_number, "
                        + "fingerprint, captured_at, received_at) VALUES ('keep-c', 'keep-o', 'KEEP-SN', 1, 'keep-f', 123456, CURRENT_TIMESTAMP(6))");
            }
        }
        assertThat(flyway(db, null).migrate().success).isTrue();
        try (Connection connection = connect(db)) {
            assertMysql(connection);
            Map<String, Installed> after = history(connection);
            before.forEach((version, row) -> assertThat(after.get(version)).isEqualTo(row));
            assertSqlChecksums(after);
            assertThat(after.get("27")).isEqualTo(new Installed("27", "JDBC", null, 1));
            assertThat(flyway(db, null).info().current().getVersion().getVersion()).isEqualTo("27");
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery("SELECT captured_at FROM dispatch_flight_sample WHERE fingerprint = 'keep-f'")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getLong(1)).isEqualTo(123456L);
            }
            for (String table : EVENT_TABLES) assertThat(tableCount(connection, table)).isEqualTo(1);
            assertColumn(connection);
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM flight_event_packet")) {
                assertThat(rs.next()).isTrue();
                assertThat(rs.getInt(1)).isZero();
            }
        }
    }

    private static void assertColumn(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            assertThat(text(statement, "SELECT DATA_TYPE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() "
                    + "AND TABLE_NAME = 'flight_event_chain' AND COLUMN_NAME = 'serial_number'")).isEqualToIgnoringCase("varbinary");
            assertThat(query(statement, "SELECT COLLATION_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() "
                    + "AND TABLE_NAME = 'flight_event_chain' AND COLUMN_NAME = 'serial_number'")).isNull();
            assertThat(text(statement, "SELECT COLLATION_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() "
                    + "AND TABLE_NAME = 'flight_event_chain' AND COLUMN_NAME = 'chain_id'")).isEqualToIgnoringCase("utf8mb4_bin");
            assertThat(text(statement, "SELECT DATA_TYPE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() "
                    + "AND TABLE_NAME = 'flight_event_packet' AND COLUMN_NAME = 'raw_bytes'")).isEqualToIgnoringCase("mediumblob");
            String order = text(statement, "SELECT COLLATION_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() "
                    + "AND TABLE_NAME = 'dispatch_order' AND COLUMN_NAME = 'id'");
            String eventOrder = text(statement, "SELECT COLLATION_NAME FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() "
                    + "AND TABLE_NAME = 'flight_event_chain' AND COLUMN_NAME = 'order_id'");
            assertThat(eventOrder).isEqualTo(order);
        }
    }

    private static Flyway flyway(EventMysql.Db db, String target) {
        var configured = Flyway.configure().dataSource(db.url(), db.user(), db.password()).locations("classpath:db/migration");
        if (target != null) configured.target(target);
        return configured.load();
    }

    private static Connection connect(EventMysql.Db db) throws Exception {
        return DriverManager.getConnection(db.url(), db.user(), db.password());
    }

    private static void assertMysql(Connection connection) throws Exception {
        String product = connection.getMetaData().getDatabaseProductName();
        String version = connection.getMetaData().getDatabaseProductVersion();
        System.out.println("[event-mysql] product=" + product + " version=" + version + " catalog=" + connection.getCatalog());
        assertThat(product.toLowerCase()).contains("mysql");
        assertThat(version).isNotBlank();
        assertThat(connection.getCatalog()).isIn(EventMysql.NAMES);
    }

    private static int tableCount(Connection connection, String table) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM information_schema.tables "
                     + "WHERE table_schema = DATABASE() AND table_name = '" + table + "'")) {
            assertThat(rs.next()).isTrue();
            return rs.getInt(1);
        }
    }

    private static Map<String, Installed> history(Connection connection) throws Exception {
        var map = new LinkedHashMap<String, Installed>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery(
                     "SELECT version, type, checksum, success FROM flyway_schema_history WHERE version IS NOT NULL")) {
            while (rs.next()) {
                String version = rs.getString(1);
                String type = rs.getString(2);
                int raw = rs.getInt(3);
                Integer checksum = rs.wasNull() ? null : raw;
                map.put(version, new Installed(version, type, checksum, rs.getInt(4)));
            }
        }
        return map;
    }

    private static void assertSqlChecksums(Map<String, Installed> rows) {
        assertThat(rows.values()).anyMatch(row -> "SQL".equals(row.type()));
        for (Installed row : rows.values()) {
            assertThat(row.success()).isEqualTo(1);
            if ("SQL".equals(row.type())) assertThat(row.checksum()).isNotNull();
        }
    }

    private record Installed(String version, String type, Integer checksum, int success) { }

    private static String text(Statement statement, String sql) throws Exception {
        String value = query(statement, sql);
        assertThat(value).isNotBlank();
        return value;
    }

    private static String query(Statement statement, String sql) throws Exception {
        try (ResultSet rs = statement.executeQuery(sql)) {
            assertThat(rs.next()).isTrue();
            return rs.getString(1);
        }
    }
}

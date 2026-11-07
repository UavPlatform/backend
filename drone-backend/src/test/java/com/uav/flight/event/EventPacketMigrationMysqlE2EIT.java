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
import java.util.Objects;

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
        try (Connection connection = connect(db)) {
            assertMysql(connection);
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
        Map<String, Integer> before;
        try (Connection connection = connect(db)) {
            before = checksums(connection);
            assertThat(before).containsKey("26").doesNotContainKey("27");
            try (Statement statement = connection.createStatement()) {
                statement.execute("INSERT INTO dispatch_flight_sample (connection_id, order_id, serial_number, sequence_number, "
                        + "fingerprint, captured_at, received_at) VALUES ('keep-c', 'keep-o', 'KEEP-SN', 1, 'keep-f', 123456, CURRENT_TIMESTAMP(6))");
            }
        }
        assertThat(flyway(db, null).migrate().success).isTrue();
        try (Connection connection = connect(db)) {
            assertMysql(connection);
            Map<String, Integer> after = checksums(connection);
            before.forEach((version, checksum) -> assertThat(after).containsEntry(version, checksum));
            assertThat(after).containsKey("27");
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

    private static Map<String, Integer> checksums(Connection connection) throws Exception {
        var map = new LinkedHashMap<String, Integer>();
        try (Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT version, checksum FROM flyway_schema_history WHERE success = 1")) {
            while (rs.next()) {
                int checksum = rs.getInt(2);
                map.put(rs.getString(1), rs.wasNull() ? null : checksum);
            }
        }
        map.values().forEach(Objects::requireNonNull);
        return map;
    }

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

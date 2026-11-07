package db.migration;

import org.flywaydb.core.api.migration.BaseJavaMigration;
import org.flywaydb.core.api.migration.Context;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Draft event-packet store. Does not alter V1–V26 objects or their checksums.
 * Raw packets, receipts, per-event results, facts, chain position and conflict evidence are separate tables.
 * They are not telemetry samples: {@code TelemetryRetentionJob} only drops {@code dispatch_flight_sample}.
 * No retention job is added here. Permanent retention is not approved.
 * Serial, source and model are VARBINARY so MySQL's default case-insensitive collation cannot merge them.
 * UUID columns are utf8mb4_bin. {@code order_id} keeps {@code dispatch_order.id}'s collation so the foreign key is legal.
 */
public class V27__flight_event_packets extends BaseJavaMigration {
    private static final Pattern COLLATION = Pattern.compile("[A-Za-z0-9_]+");

    @Override
    public void migrate(Context context) throws Exception {
        Connection connection = context.getConnection();
        boolean mysql = connection.getMetaData().getDatabaseProductName().toLowerCase(Locale.ROOT).contains("mysql");
        String orderCollation = mysql ? orderCollation(connection) : "";
        try (Statement statement = connection.createStatement()) {
            statement.execute(chain(mysql, orderCollation));
            statement.execute(packet(mysql, orderCollation));
            statement.execute(receipt(mysql));
            statement.execute(result(mysql));
            statement.execute(fact(mysql, orderCollation));
            statement.execute(conflict(mysql, orderCollation));
            statement.execute("CREATE INDEX idx_flight_event_chain_order ON flight_event_chain(order_id, chain_id)");
            statement.execute("CREATE INDEX idx_flight_event_fact_interval ON flight_event_fact(chain_id, event_type, captured_at_ms)");
        }
    }

    private static String orderCollation(Connection connection) throws SQLException {
        try (var statement = connection.createStatement();
             var rs = statement.executeQuery("SELECT COLLATION_NAME FROM information_schema.COLUMNS "
                     + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'dispatch_order' AND COLUMN_NAME = 'id'")) {
            if (!rs.next()) throw new SQLException("dispatch_order.id is missing; V27 will not guess a collation");
            String collation = rs.getString(1);
            if (collation == null || !COLLATION.matcher(collation).matches()) {
                throw new SQLException("dispatch_order.id collation is not a safe identifier");
            }
            return collation;
        }
    }

    private static String chain(boolean mysql, String orderCollation) {
        return "CREATE TABLE flight_event_chain ("
                + "chain_id " + uuid(mysql) + " NOT NULL PRIMARY KEY,"
                + "order_id " + orderId(mysql, orderCollation) + " NOT NULL,"
                + "assignment_version BIGINT NOT NULL,"
                + "pilot_id BIGINT NOT NULL,"
                + "recording_source_id " + uuid(mysql) + " NOT NULL,"
                + "serial_number VARBINARY(64) NOT NULL,"
                + "serial_source VARBINARY(64) NOT NULL,"
                + "model_code VARBINARY(128) NOT NULL,"
                + "schema_version INT NOT NULL,"
                + "hash_version INT NOT NULL,"
                + "anchor_hash BINARY(32) NOT NULL,"
                + "contiguous_height BIGINT NOT NULL,"
                + "contiguous_hash BINARY(32),"
                + "highest_received_height BIGINT NOT NULL,"
                + "integrity VARCHAR(16) NOT NULL,"
                + "validation_version BIGINT NOT NULL,"
                + "projection_revision BIGINT NOT NULL,"
                + "created_at_ms BIGINT NOT NULL,"
                + "CONSTRAINT uk_flight_event_binding UNIQUE (order_id, assignment_version, recording_source_id, serial_number, serial_source, model_code),"
                + "CONSTRAINT fk_flight_event_chain_order FOREIGN KEY (order_id) REFERENCES dispatch_order(id),"
                + "CONSTRAINT fk_flight_event_chain_pilot FOREIGN KEY (pilot_id) REFERENCES user(id)"
                + ")" + tail(mysql);
    }

    private static String packet(boolean mysql, String orderCollation) {
        return "CREATE TABLE flight_event_packet ("
                + "packet_id " + uuid(mysql) + " NOT NULL PRIMARY KEY,"
                + "chain_id " + uuid(mysql) + " NOT NULL,"
                + "order_id " + orderId(mysql, orderCollation) + " NOT NULL,"
                + "height BIGINT NOT NULL,"
                + "prev_packet_id " + uuid(mysql) + ","
                + "prev_chain_hash BINARY(32) NOT NULL,"
                + "chain_hash BINARY(32) NOT NULL,"
                + "content_digest BINARY(32) NOT NULL,"
                + "raw_sha256 BINARY(32) NOT NULL,"
                + "raw_bytes " + blob(mysql) + " NOT NULL,"
                + "canonical_utf8 " + blob(mysql) + " NOT NULL,"
                + "decided_at_ms BIGINT NOT NULL,"
                + "CONSTRAINT uk_flight_event_height UNIQUE (chain_id, height),"
                + "CONSTRAINT fk_flight_event_packet_chain FOREIGN KEY (chain_id) REFERENCES flight_event_chain(chain_id),"
                + "CONSTRAINT fk_flight_event_packet_order FOREIGN KEY (order_id) REFERENCES dispatch_order(id)"
                + ")" + tail(mysql);
    }

    private static String receipt(boolean mysql) {
        return "CREATE TABLE flight_event_receipt ("
                + "packet_id " + uuid(mysql) + " NOT NULL PRIMARY KEY,"
                + "receipt_version INT NOT NULL,"
                + "content_digest BINARY(32) NOT NULL,"
                + "chain_hash BINARY(32) NOT NULL,"
                + "durability VARCHAR(40) NOT NULL,"
                + "decided_at_ms BIGINT NOT NULL,"
                + "CONSTRAINT fk_flight_event_receipt_packet FOREIGN KEY (packet_id) REFERENCES flight_event_packet(packet_id)"
                + ")" + tail(mysql);
    }

    private static String result(boolean mysql) {
        return "CREATE TABLE flight_event_result ("
                + "packet_id " + uuid(mysql) + " NOT NULL,"
                + "event_ordinal INT NOT NULL,"
                + "event_id " + uuid(mysql) + " NOT NULL,"
                + "outcome VARCHAR(16) NOT NULL,"
                + "reason VARCHAR(40),"
                + "source_packet_id " + uuid(mysql) + ","
                + "PRIMARY KEY (packet_id, event_ordinal),"
                + "CONSTRAINT uk_flight_event_result_event UNIQUE (packet_id, event_id),"
                + "CONSTRAINT fk_flight_event_result_packet FOREIGN KEY (packet_id) REFERENCES flight_event_packet(packet_id)"
                + ")" + tail(mysql);
    }

    private static String fact(boolean mysql, String orderCollation) {
        return "CREATE TABLE flight_event_fact ("
                + "event_id " + uuid(mysql) + " NOT NULL PRIMARY KEY,"
                + "packet_id " + uuid(mysql) + " NOT NULL,"
                + "chain_id " + uuid(mysql) + " NOT NULL,"
                + "order_id " + orderId(mysql, orderCollation) + " NOT NULL,"
                + "assignment_version BIGINT NOT NULL,"
                + "recording_source_id " + uuid(mysql) + " NOT NULL,"
                + "serial_number VARBINARY(64) NOT NULL,"
                + "serial_source VARBINARY(64) NOT NULL,"
                + "model_code VARBINARY(128) NOT NULL,"
                + "event_type VARCHAR(32) NOT NULL,"
                + "captured_at_ms BIGINT NOT NULL,"
                + "payload_canonical " + blob(mysql) + " NOT NULL,"
                + "CONSTRAINT fk_flight_event_fact_packet FOREIGN KEY (packet_id) REFERENCES flight_event_packet(packet_id),"
                + "CONSTRAINT fk_flight_event_fact_order FOREIGN KEY (order_id) REFERENCES dispatch_order(id)"
                + ")" + tail(mysql);
    }

    private static String conflict(boolean mysql, String orderCollation) {
        return "CREATE TABLE flight_event_conflict ("
                + "id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,"
                + "kind VARCHAR(32) NOT NULL,"
                + "order_id " + orderId(mysql, orderCollation) + " NOT NULL,"
                + "chain_id " + uuid(mysql) + ","
                + "packet_id " + uuid(mysql) + ","
                + "raw_sha256 BINARY(32) NOT NULL,"
                + "raw_bytes " + blob(mysql) + " NOT NULL,"
                + "detail VARCHAR(200) NOT NULL,"
                + "created_at_ms BIGINT NOT NULL,"
                + "CONSTRAINT fk_flight_event_conflict_order FOREIGN KEY (order_id) REFERENCES dispatch_order(id)"
                + ")" + tail(mysql);
    }

    private static String uuid(boolean mysql) {
        return mysql ? "VARCHAR(36) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin" : "VARCHAR(36)";
    }

    private static String orderId(boolean mysql, String collation) {
        return mysql ? "VARCHAR(36) CHARACTER SET utf8mb4 COLLATE " + collation : "VARCHAR(36)";
    }

    private static String blob(boolean mysql) { return mysql ? "MEDIUMBLOB" : "BLOB"; }

    private static String tail(boolean mysql) {
        return mysql ? " ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin" : "";
    }
}

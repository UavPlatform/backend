package com.uav.flight.event;

import org.testcontainers.mysql.MySQLContainer;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Disposable MySQL for event-packet acceptance. Uses EVENT_MYSQL_* when set, otherwise Testcontainers mysql:8.4.
 * Refuses port 3306 and the operator or shared CI databases. Never skips: a missing database fails the test.
 */
public final class EventMysql {
    public static final Set<String> NAMES = Set.of("event_http", "event_upgrade", "event_empty");
    private static final Pattern PORT = Pattern.compile(":(\\d+)/");
    private static MySQLContainer container;

    private EventMysql() { }

    public record Db(String url, String user, String password) { }

    public static Db http() { return resolve("EVENT_MYSQL_HTTP_URL", "event_http"); }
    public static Db upgrade() { return resolve("EVENT_MYSQL_UPGRADE_URL", "event_upgrade"); }
    public static Db empty() { return resolve("EVENT_MYSQL_EMPTY_URL", "event_empty"); }

    public static void wipe(Db db) throws SQLException {
        try (Connection connection = DriverManager.getConnection(db.url(), db.user(), db.password())) {
            String catalog = connection.getCatalog();
            if (!NAMES.contains(catalog)) throw new IllegalStateException("refusing to wipe catalog " + catalog);
            try (Statement statement = connection.createStatement()) {
                statement.execute("SET FOREIGN_KEY_CHECKS = 0");
                var names = new ArrayList<String>();
                try (var rs = statement.executeQuery("SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema = DATABASE() AND table_type = 'BASE TABLE'")) {
                    while (rs.next()) names.add(rs.getString(1));
                }
                for (String name : names) {
                    if (!name.matches("[A-Za-z0-9_]+")) throw new IllegalStateException("unsafe table " + name);
                    statement.execute("DROP TABLE `" + name + "`");
                }
                statement.execute("SET FOREIGN_KEY_CHECKS = 1");
            }
        }
    }

    private static Db resolve(String variable, String name) {
        String configured = System.getenv(variable);
        boolean any = getenv("EVENT_MYSQL_HTTP_URL") != null || getenv("EVENT_MYSQL_UPGRADE_URL") != null
                || getenv("EVENT_MYSQL_EMPTY_URL") != null;
        if (any) {
            if (configured == null || configured.isBlank()) {
                throw new IllegalStateException(variable + " is required when any EVENT_MYSQL_* URL is set");
            }
            refuse(configured, name);
            String user = getenv("EVENT_MYSQL_USER");
            String password = getenv("EVENT_MYSQL_PASSWORD");
            if (user == null || user.isBlank() || password == null) {
                throw new IllegalStateException("EVENT_MYSQL_USER and EVENT_MYSQL_PASSWORD are required");
            }
            return new Db(configured, user, password);
        }
        start();
        String url = withDatabase(container.getJdbcUrl(), name);
        refuse(url, name);
        return new Db(url, container.getUsername(), container.getPassword());
    }

    private static synchronized void start() {
        if (container != null) return;
        container = new MySQLContainer("mysql:8.4");
        container.withUsername("root");
        try {
            container.start();
            try (Connection connection = DriverManager.getConnection(container.getJdbcUrl(), container.getUsername(), container.getPassword());
                 Statement statement = connection.createStatement()) {
                for (String name : NAMES) statement.execute("CREATE DATABASE IF NOT EXISTS `" + name + "`");
            }
        } catch (RuntimeException | SQLException e) {
            throw new IllegalStateException("MySQL is required. Set EVENT_MYSQL_HTTP_URL, EVENT_MYSQL_UPGRADE_URL, "
                    + "EVENT_MYSQL_EMPTY_URL, EVENT_MYSQL_USER and EVENT_MYSQL_PASSWORD, or provide Docker for "
                    + "Testcontainers mysql:8.4. This test does not skip.", e);
        }
    }

    private static void refuse(String url, String name) {
        var port = PORT.matcher(url);
        if (!port.find() || "3306".equals(port.group(1))) throw new IllegalStateException("refusing MySQL port 3306");
        if (url.contains("/drone_db") || url.contains("/drone_ci")) {
            throw new IllegalStateException("refusing operator or shared CI database");
        }
        if (!url.contains("/" + name)) throw new IllegalStateException("URL is not the disposable database " + name);
    }

    private static String withDatabase(String jdbc, String name) {
        int scheme = jdbc.indexOf("://");
        int slash = jdbc.indexOf('/', scheme + 3);
        int query = jdbc.indexOf('?', slash);
        return jdbc.substring(0, slash + 1) + name + (query < 0 ? "" : jdbc.substring(query));
    }

    private static String getenv(String name) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? null : value;
    }
}

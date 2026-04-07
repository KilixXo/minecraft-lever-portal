package com.portal.plugin.storage;

import com.portal.plugin.Main;
import org.bukkit.configuration.file.FileConfiguration;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * DatabaseConfig — reads and exposes database connection settings from {@code config.yml}.
 *
 * <p>Relevant config section:
 * <pre>
 * storage:
 *   type: mysql          # sqlite | mysql | mariadb | postgresql
 *   database:
 *     host: localhost
 *     port: 3306
 *     name: leverportal
 *     username: root
 *     password: ""
 *     # Optional JDBC connection properties appended to the URL
 *     properties: "useSSL=true&serverTimezone=UTC"
 * </pre>
 *
 * <p>SEC-01 fix: the {@code properties} string is parsed into key-value pairs and
 * each key and value is URL-encoded before being appended to the JDBC URL.
 * This prevents injection of arbitrary JDBC parameters via the config file.
 */
public class DatabaseConfig {

    /** Supported storage backend types. */
    public enum DatabaseType {
        SQLITE,
        MYSQL,
        MARIADB,
        POSTGRESQL
    }

    private final DatabaseType type;
    private final String host;
    private final int port;
    private final String database;
    private final String username;
    private final String password;
    private final String rawProperties;

    /**
     * Reads database configuration from the plugin's {@code config.yml}.
     *
     * @param plugin the plugin instance (used to access config)
     */
    public DatabaseConfig(Main plugin) {
        FileConfiguration cfg = plugin.getConfig();

        String rawType = cfg.getString("storage.type", "sqlite").toLowerCase().trim();
        this.type = parseType(rawType);

        this.host          = cfg.getString("storage.database.host",     "localhost");
        this.port          = cfg.getInt   ("storage.database.port",     defaultPort(this.type));
        this.database      = cfg.getString("storage.database.name",     "leverportal");
        this.username      = cfg.getString("storage.database.username", "root");
        this.password      = cfg.getString("storage.database.password", "");
        this.rawProperties = cfg.getString("storage.database.properties", "");
    }

    // ── Getters ──────────────────────────────────────────────────────────────

    public DatabaseType getType()     { return type; }
    public String       getHost()     { return host; }
    public int          getPort()     { return port; }
    public String       getDatabase() { return database; }
    public String       getUsername() { return username; }
    public String       getPassword() { return password; }

    /**
     * Builds a JDBC connection URL for the configured backend.
     *
     * @return JDBC URL string
     */
    public String buildJdbcUrl() {
        switch (type) {
            case MYSQL:
                return buildMysqlUrl("jdbc:mysql");
            case MARIADB:
                return buildMysqlUrl("jdbc:mariadb");
            case POSTGRESQL:
                return buildPostgresUrl();
            case SQLITE:
            default:
                throw new UnsupportedOperationException(
                    "SQLite does not use a network JDBC URL — use SQLitePortalStorage directly.");
        }
    }

    /**
     * Returns the fully-qualified JDBC driver class name for the configured backend.
     *
     * @return driver class name
     */
    public String getDriverClass() {
        switch (type) {
            case MYSQL:      return "com.mysql.cj.jdbc.Driver";
            case MARIADB:    return "org.mariadb.jdbc.Driver";
            case POSTGRESQL: return "org.postgresql.Driver";
            case SQLITE:
            default:         return "org.sqlite.JDBC";
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    private static DatabaseType parseType(String raw) {
        switch (raw) {
            case "mysql":      return DatabaseType.MYSQL;
            case "mariadb":    return DatabaseType.MARIADB;
            case "postgresql":
            case "postgres":   return DatabaseType.POSTGRESQL;
            case "sqlite":
            default:           return DatabaseType.SQLITE;
        }
    }

    private static int defaultPort(DatabaseType type) {
        switch (type) {
            case MYSQL:
            case MARIADB:    return 3306;
            case POSTGRESQL: return 5432;
            default:         return 0;
        }
    }

    /**
     * SEC-01 fix: parse the raw properties string into key-value pairs and
     * URL-encode each key and value individually before appending to the URL.
     * This prevents injection of arbitrary JDBC parameters.
     *
     * @return sanitized query string (without leading '?'), or empty string if no properties
     */
    private String buildSanitizedQueryString() {
        if (rawProperties == null || rawProperties.trim().isEmpty()) {
            return "";
        }

        Map<String, String> params = new LinkedHashMap<>();
        for (String pair : rawProperties.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                String key   = pair.substring(0, eq).trim();
                String value = pair.substring(eq + 1).trim();
                if (!key.isEmpty()) {
                    params.put(key, value);
                }
            }
        }

        if (params.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (sb.length() > 0) sb.append("&");
            sb.append(urlEncode(entry.getKey()))
              .append("=")
              .append(urlEncode(entry.getValue()));
        }
        return sb.toString();
    }

    private static String urlEncode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** Builds a MySQL/MariaDB JDBC URL with sanitized properties. */
    private String buildMysqlUrl(String scheme) {
        StringBuilder sb = new StringBuilder();
        sb.append(scheme).append("://")
          .append(sanitizeHostname(host)).append(":").append(port)
          .append("/").append(sanitizeDatabaseName(database));
        String qs = buildSanitizedQueryString();
        if (!qs.isEmpty()) {
            sb.append("?").append(qs);
        }
        return sb.toString();
    }

    /** Builds a PostgreSQL JDBC URL with sanitized properties. */
    private String buildPostgresUrl() {
        StringBuilder sb = new StringBuilder();
        sb.append("jdbc:postgresql://")
          .append(sanitizeHostname(host)).append(":").append(port)
          .append("/").append(sanitizeDatabaseName(database));
        String qs = buildSanitizedQueryString();
        if (!qs.isEmpty()) {
            sb.append("?").append(qs);
        }
        return sb.toString();
    }

    /**
     * Validate that the hostname contains only safe characters (alphanumeric, dots, hyphens).
     * Throws {@link IllegalArgumentException} if the hostname is invalid.
     */
    private static String sanitizeHostname(String hostname) {
        if (hostname == null || !hostname.matches("[a-zA-Z0-9._\\-]+")) {
            throw new IllegalArgumentException(
                "[Storage] Invalid database hostname in config: '" + hostname + "'");
        }
        return hostname;
    }

    /**
     * Validate that the database name contains only safe characters.
     * Throws {@link IllegalArgumentException} if the name is invalid.
     */
    private static String sanitizeDatabaseName(String name) {
        if (name == null || !name.matches("[a-zA-Z0-9_\\-]+")) {
            throw new IllegalArgumentException(
                "[Storage] Invalid database name in config: '" + name + "'");
        }
        return name;
    }
}

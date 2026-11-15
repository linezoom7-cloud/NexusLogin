package dev.nexus.login.config;

import dev.nexus.login.database.DatabaseType;
import org.bukkit.configuration.ConfigurationSection;

import java.util.Locale;
import java.util.Objects;
import java.util.Set;

public record DatabaseSettings(
        DatabaseType type,
        String sqliteFile,
        String host,
        int port,
        String database,
        String username,
        String password,
        String sslMode,
        int maximumPoolSize,
        int minimumIdle,
        long connectionTimeoutMs,
        long maxLifetimeMs
) {

    /** MariaDB Connector/J tarafından kabul edilen TLS kipleri. */
    private static final Set<String> SSL_MODES =
            Set.of("disable", "trust", "verify-ca", "verify-full");

    public DatabaseSettings {
        Objects.requireNonNull(type, "type");
        sslMode = sslMode == null ? "" : sslMode.trim().toLowerCase(Locale.ROOT);
        if (type == DatabaseType.SQLITE) {
            requireNotBlank(sqliteFile, "database.sqlite.file");
        } else if (type == DatabaseType.MARIADB) {
            requireNotBlank(host, "database.mariadb.host");
            requireNotBlank(database, "database.mariadb.database");
            requireNotBlank(username, "database.mariadb.username");
            // Host ve veritabanı adı JDBC adresine gömülür; '/', '?', '&' gibi karakterler
            // adrese ek sürücü parametresi (ör. allowLocalInfile=true) enjekte edebilirdi.
            if (!host.trim().matches("[A-Za-z0-9.\\-]+|\\[[0-9A-Fa-f:.]+\\]")) {
                throw new IllegalArgumentException("database.mariadb.host geçersiz: '" + host + "'");
            }
            if (!database.matches("[A-Za-z0-9_$\\-]{1,64}")) {
                throw new IllegalArgumentException("database.mariadb.database yalnızca harf, rakam, _ $ - içerebilir");
            }
            if (port < 1 || port > 65_535) {
                throw new IllegalArgumentException("database.mariadb.port 1-65535 aralığında olmalı: " + port);
            }
            if (!SSL_MODES.contains(sslMode)) {
                throw new IllegalArgumentException("database.mariadb.ssl-mode geçersiz: '" + sslMode
                        + "' (disable, trust, verify-ca veya verify-full olmalı)");
            }
        }
        if (maximumPoolSize < 1) {
            throw new IllegalArgumentException("database.pool.maximum-pool-size >= 1 olmalı");
        }
        if (minimumIdle < 0 || minimumIdle > maximumPoolSize) {
            throw new IllegalArgumentException("database.pool.minimum-idle 0 ile maximum-pool-size arasında olmalı");
        }
        // HikariCP'nin kabul ettiği alt sınırlar.
        if (connectionTimeoutMs < 250) {
            throw new IllegalArgumentException("database.pool.connection-timeout-ms >= 250 olmalı");
        }
        if (maxLifetimeMs < 30_000) {
            throw new IllegalArgumentException("database.pool.max-lifetime-ms >= 30000 olmalı");
        }
    }

    static DatabaseSettings from(ConfigurationSection s) {
        ConfigurationSection pool = PluginSettings.section(s, "pool");
        return new DatabaseSettings(
                DatabaseType.parse(s.getString("type", "SQLITE")),
                s.getString("sqlite.file", "nexus.db"),
                s.getString("mariadb.host", "localhost"),
                s.getInt("mariadb.port", 3306),
                s.getString("mariadb.database", "nexus"),
                s.getString("mariadb.username", "nexus"),
                s.getString("mariadb.password", ""),
                // Güvenlik için önerilen: verify-full. Geriye dönük uyumluluk adına varsayılan
                // disable; uzak sunucularda açıkça yükseltilmelidir (açılışta uyarı verilir).
                s.getString("mariadb.ssl-mode", "disable"),
                pool.getInt("maximum-pool-size", 10),
                pool.getInt("minimum-idle", 2),
                pool.getLong("connection-timeout-ms", 5_000L),
                pool.getLong("max-lifetime-ms", 1_800_000L)
        );
    }

    private static void requireNotBlank(String value, String key) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(key + " boş olamaz");
        }
    }

    /** Şifrenin loglara/stack trace'lere sızmaması için record'un otomatik toString'i ezilir. */
    @Override
    public String toString() {
        if (type == DatabaseType.SQLITE) {
            return "DatabaseSettings[type=SQLITE, file=%s]".formatted(sqliteFile);
        }
        return "DatabaseSettings[type=%s, host=%s, port=%d, database=%s, user=%s, sslMode=%s, password=***]"
                .formatted(type, host, port, database, username, sslMode);
    }
}

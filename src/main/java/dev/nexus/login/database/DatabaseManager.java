package dev.nexus.login.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.nexus.login.config.DatabaseSettings;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.logging.Logger;

/**
 * HikariCP bağlantı havuzunun yaşam döngüsü.
 *
 * <p>Havuz kurucuda DEĞİL, {@link #start()} içinde, G/Ç executor'ünde oluşturulur:
 * {@code new HikariDataSource(config)} ilk bağlantıyı senkron açar ve uzak bir MariaDB'de
 * saniyelerce sürebilir. Bunu onEnable içinde yapmak ana thread'i dondururdu.
 */
public final class DatabaseManager implements AutoCloseable {

    private final DatabaseSettings settings;
    private final Path dataFolder;
    private final Executor ioExecutor;
    private final Logger logger;

    private volatile HikariDataSource dataSource;

    public DatabaseManager(DatabaseSettings settings, Path dataFolder, Executor ioExecutor, Logger logger) {
        this.settings = settings;
        this.dataFolder = dataFolder;
        this.ioExecutor = ioExecutor;
        this.logger = logger;
    }

    public DatabaseType type() {
        return settings.type();
    }

    /** Havuzu açar ve şemayı oluşturur. Tamamlanana kadar {@link #getConnection()} kullanılamaz. */
    public CompletableFuture<Void> start() {
        return CompletableFuture.runAsync(() -> {
            try {
                Files.createDirectories(dataFolder);
                HikariDataSource ds = new HikariDataSource(buildConfig());
                try {
                    migrate(ds);
                } catch (SQLException e) {
                    ds.close();
                    throw e;
                }
                this.dataSource = ds;
                logger.info(() -> "Veritabanı hazır (" + settings.type() + ", havuz="
                        + ds.getMaximumPoolSize() + ").");
            } catch (Exception e) {
                throw new DataAccessException("Veritabanı başlatılamadı: " + e.getMessage(), e);
            }
        }, ioExecutor);
    }

    private HikariConfig buildConfig() {
        HikariConfig config = new HikariConfig();
        config.setPoolName("NexusLogin-" + settings.type().name());
        config.setDriverClassName(settings.type().driverClassName());
        config.setConnectionTimeout(settings.connectionTimeoutMs());
        config.setMaxLifetime(settings.maxLifetimeMs());

        switch (settings.type()) {
            case SQLITE -> {
                Path file = dataFolder.resolve(settings.sqliteFile()).toAbsolutePath();
                config.setJdbcUrl("jdbc:sqlite:" + file);
                // SQLite tek yazıcıya izin verir. Birden fazla bağlantı = SQLITE_BUSY hataları.
                // Tek bağlantı + Hikari kuyruğu, eşzamanlı istekleri güvenle sıraya koyar.
                config.setMaximumPoolSize(1);
                config.setMinimumIdle(1);
                // sqlite-jdbc bu anahtarları PRAGMA olarak uygular.
                config.addDataSourceProperty("journal_mode", "WAL");
                config.addDataSourceProperty("synchronous", "NORMAL");
                config.addDataSourceProperty("busy_timeout", "5000");
                config.addDataSourceProperty("foreign_keys", "true");
            }
            case MARIADB -> {
                config.setJdbcUrl("jdbc:mariadb://%s:%d/%s".formatted(
                        settings.host(), settings.port(), settings.database()));
                config.setUsername(settings.username());
                config.setPassword(settings.password());
                config.setMaximumPoolSize(settings.maximumPoolSize());
                config.setMinimumIdle(settings.minimumIdle());
                // MariaDB Connector/J 3.x her zaman utf8mb4 konuşur; ek encoding ayarı gerekmez.
                config.addDataSourceProperty("cachePrepStmts", "true");
                config.addDataSourceProperty("prepStmtCacheSize", "250");
                config.addDataSourceProperty("useServerPrepStmts", "true");
            }
        }
        return config;
    }

    private void migrate(HikariDataSource ds) throws SQLException {
        try (Connection connection = ds.getConnection()) {
            try (Statement statement = connection.createStatement()) {
                for (String sql : Schema.statements(settings.type())) {
                    statement.execute(sql);
                }
            }

            int current = readVersion(connection);
            if (current > Schema.VERSION) {
                // Eski eklenti yeni şemayı bozmasın: sessizce devam etmek yerine net hata ver.
                throw new SQLException("Veritabanı şeması bu eklentiden daha yeni (veritabanı v"
                        + current + ", eklenti v" + Schema.VERSION + "). Lütfen eklentiyi güncelleyin.");
            }
            if (current < Schema.VERSION) {
                applyMigrations(connection, current);
                writeVersion(connection, Schema.VERSION);
                int target = Schema.VERSION;
                logger.info(() -> "Veritabanı şeması güncellendi: v" + current + " → v" + target + ".");
            }
        }
    }

    /**
     * Sürümler arası veri dönüşümleri. Temel tablolar {@link Schema#statements} ile her
     * açılışta oluşturulduğu için v0 → v1 geçişi ek işlem gerektirmez; gelecekteki
     * değişiklikler buraya sırayla eklenir.
     */
    private void applyMigrations(Connection connection, int fromVersion) throws SQLException {
        // Örnek gelecek adımı:
        // if (fromVersion < 2) { ... ALTER TABLE ... }
    }

    private int readVersion(Connection connection) throws SQLException {
        String sql = "SELECT meta_value FROM nexus_meta WHERE meta_key = ?";
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, Schema.VERSION_KEY);
            try (var result = statement.executeQuery()) {
                if (!result.next()) {
                    return 0;
                }
                try {
                    return Integer.parseInt(result.getString(1).trim());
                } catch (NumberFormatException e) {
                    throw new SQLException("nexus_meta." + Schema.VERSION_KEY + " sayısal değil", e);
                }
            }
        }
    }

    private void writeVersion(Connection connection, int version) throws SQLException {
        String sql = switch (settings.type()) {
            // MariaDB: tek sorguda ekle/güncelle. SQLite: ON CONFLICT (3.24+).
            case MARIADB -> "INSERT INTO nexus_meta (meta_key, meta_value) VALUES (?, ?) "
                    + "ON DUPLICATE KEY UPDATE meta_value = VALUES(meta_value)";
            case SQLITE -> "INSERT INTO nexus_meta (meta_key, meta_value) VALUES (?, ?) "
                    + "ON CONFLICT(meta_key) DO UPDATE SET meta_value = excluded.meta_value";
        };
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, Schema.VERSION_KEY);
            statement.setString(2, Integer.toString(version));
            statement.executeUpdate();
        }
    }

    /**
     * Havuzdan bağlantı alır. Ana thread'den ÇAĞRILMAMALIDIR; yalnızca
     * repository'lerin G/Ç executor'ü içinde kullanılır.
     */
    public Connection getConnection() throws SQLException {
        HikariDataSource ds = this.dataSource;
        if (ds == null || ds.isClosed()) {
            throw new SQLException("Veritabanı havuzu açık değil");
        }
        return ds.getConnection();
    }

    @Override
    public void close() {
        HikariDataSource ds = this.dataSource;
        this.dataSource = null;
        if (ds != null && !ds.isClosed()) {
            ds.close();
        }
    }
}

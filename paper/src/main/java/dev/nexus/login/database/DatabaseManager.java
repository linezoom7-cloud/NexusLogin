package dev.nexus.login.database;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.nexus.login.config.DatabaseSettings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.logging.Level;
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
                Path file = sqliteFile();
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
                        settings.host().trim(), settings.port(), settings.database()));
                config.setUsername(settings.username());
                config.setPassword(settings.password());
                config.setMaximumPoolSize(settings.maximumPoolSize());
                config.setMinimumIdle(settings.minimumIdle());
                // TLS: sslMode=verify-full uzak sunucularda MITM'e karşı önerilir. Varsayılan
                // "disable" geriye dönük uyumluluk içindir; aşağıda uyarı verilir.
                config.addDataSourceProperty("sslMode", settings.sslMode());
                // LOAD DATA LOCAL INFILE: kötü niyetli/ele geçirilmiş bir DB sunucusu bu özellikle
                // oyun sunucusundaki herhangi bir dosyayı (ör. config.yml'deki pepper) okuyabilirdi.
                config.addDataSourceProperty("allowLocalInfile", "false");
                warnIfDatabaseTrafficUnencrypted();
                // MariaDB Connector/J 3.x her zaman utf8mb4 konuşur; ek encoding ayarı gerekmez.
                config.addDataSourceProperty("cachePrepStmts", "true");
                config.addDataSourceProperty("prepStmtCacheSize", "250");
                config.addDataSourceProperty("useServerPrepStmts", "true");
            }
        }
        return config;
    }

    /**
     * SQLite dosyasını eklenti klasörünün İÇİNDE tutar. {@code ../} içeren bir yol, yanlış
     * yapılandırma veya kötü niyetli bir panel kullanıcısı tarafından eklenti klasörü dışına
     * yazmak için kullanılabilirdi.
     */
    private Path sqliteFile() {
        Path root = dataFolder.toAbsolutePath().normalize();
        Path file = root.resolve(settings.sqliteFile()).normalize();
        if (!file.startsWith(root)) {
            throw new IllegalArgumentException(
                    "database.sqlite.file eklenti klasörünün dışına çıkamaz: " + settings.sqliteFile());
        }
        try {
            Files.createDirectories(file.getParent());
        } catch (IOException e) {
            throw new DataAccessException("SQLite klasörü oluşturulamadı: " + file.getParent(), e);
        }
        return file;
    }

    /** Uzak MariaDB'ye şifresiz bağlanılıyorsa yöneticiyi açıkça uyarır. */
    private void warnIfDatabaseTrafficUnencrypted() {
        if (settings.sslMode().equals("disable") && !isLoopback(settings.host())) {
            logger.warning("database.mariadb.ssl-mode=disable: veritabanı trafiği (kimlik bilgileri ve "
                    + "PIN hash'leri) ağda şifresiz gidiyor. Uzak sunucularda ssl-mode: verify-full kullanın.");
        }
    }

    private static boolean isLoopback(String host) {
        String value = host == null ? "" : host.trim().toLowerCase(Locale.ROOT);
        return value.equals("localhost") || value.equals("127.0.0.1") || value.equals("::1")
                || value.equals("0:0:0:0:0:0:0:1");
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
                backupBeforeMigration(connection, current);
                boolean oldAutoCommit = connection.getAutoCommit();
                try {
                    connection.setAutoCommit(false);
                    applyMigrations(connection, current);
                    writeVersion(connection, Schema.VERSION);
                    connection.commit();
                    int target = Schema.VERSION;
                    logger.info(() -> "Veritabanı şeması güncellendi: v" + current + " → v" + target + ".");
                } catch (Exception e) {
                    try {
                        connection.rollback();
                    } catch (SQLException rollbackEx) {
                        logger.log(Level.WARNING, "Migration geri alma (rollback) hatası", rollbackEx);
                    }
                    throw new SQLException("Şema migration başarısız oldu, değişiklikler geri alındı: v"
                            + current + " → v" + Schema.VERSION, e);
                } finally {
                    try {
                        connection.setAutoCommit(oldAutoCommit);
                    } catch (SQLException ignored) {
                    }
                }
            }
        }
    }

    private void backupBeforeMigration(Connection connection, int currentVersion) {
        switch (settings.type()) {
            case SQLITE -> {
                try {
                    Path dbFile = sqliteFile();
                    if (Files.exists(dbFile)) {
                        Path backup = dbFile.resolveSibling(dbFile.getFileName().toString() + ".backup-v"
                                + currentVersion + "-" + System.currentTimeMillis());
                        // Dosya kopyası WAL'daki henüz işlenmemiş yazmaları kaçırır; VACUUM INTO tutarlı anlık görüntü verir.
                        try (Statement statement = connection.createStatement()) {
                            statement.execute("VACUUM INTO '" + backup.toAbsolutePath().toString().replace("'", "''") + "'");
                        }
                        logger.info(() -> "Şema güncellemesi öncesi SQLite yedeği alındı: " + backup.getFileName());
                    }
                } catch (Exception e) {
                    logger.log(Level.WARNING, "SQLite migration öncesi otomatik yedek alınamadı", e);
                }
            }
            case MARIADB -> {
                try (Statement statement = connection.createStatement()) {
                    // AS SELECT anahtarları/indeksleri kaybeder; LIKE yapıyı korur. IGNORE: yarım kalmış önceki denemeyi tamamlar.
                    String table = "nexus_accounts_backup_v" + currentVersion;
                    statement.execute("CREATE TABLE IF NOT EXISTS " + table + " LIKE nexus_accounts");
                    statement.execute("INSERT IGNORE INTO " + table + " SELECT * FROM nexus_accounts");
                    logger.info(() -> "Şema güncellemesi öncesi MariaDB yedek tablosu oluşturuldu: nexus_accounts_backup_v"
                            + currentVersion);
                } catch (Exception e) {
                    logger.log(Level.WARNING, "MariaDB migration öncesi yedek tablosu oluşturulamadı", e);
                }
            }
        }
    }

    /**
     * Sürümler arası veri dönüşümleri. Temel tablolar {@link Schema#statements} ile her
     * açılışta oluşturulduğu için v0 → v1 geçişi ek işlem gerektirmez; sonraki adımlar
     * buraya sırayla eklenir.
     */
    private void applyMigrations(Connection connection, int fromVersion) throws SQLException {
        if (fromVersion < 2) {
            // v2: aynı isimle (büyük/küçük harf farkı dahil) birden fazla hesap olamaz.
            // Önce yinelenenleri temizle, SONRA unique indeksi oluştur; tersi sıra mevcut
            // veritabanlarında migration'ı kalıcı olarak kilitleyebilirdi.
            dedupeAccountNames(connection);
            createUniqueNameIndex(connection);
        }
    }

    /**
     * Aynı oyuncu adına sahip satırlardan yalnızca en eskisini (en küçük id) bırakır.
     * Silinen satırların yerine yeni hesap açılabilir; bu, şema tekilliğini bozan
     * bozuk veriyi onarmak için bilinçli bir veri kaybıdır.
     */
    private void dedupeAccountNames(Connection connection) throws SQLException {
        String sql = switch (settings.type()) {
            case SQLITE -> """
                    DELETE FROM nexus_accounts
                    WHERE id NOT IN (
                        SELECT MIN(id) FROM nexus_accounts GROUP BY player_name COLLATE NOCASE
                    )
                    """;
            case MARIADB -> """
                    DELETE older FROM nexus_accounts older
                    JOIN nexus_accounts newer
                      ON older.player_name = newer.player_name AND older.id > newer.id
                    """;
        };
        try (Statement statement = connection.createStatement()) {
            int removed = statement.executeUpdate(sql);
            if (removed > 0) {
                logger.warning(() -> "Şema v2: isim çakışması olan " + removed
                        + " yinelenen hesap satırı silindi.");
            }
        }
    }

    private void createUniqueNameIndex(Connection connection) throws SQLException {
        switch (settings.type()) {
            case SQLITE -> {
                try (Statement statement = connection.createStatement()) {
                    statement.execute("""
                            CREATE UNIQUE INDEX IF NOT EXISTS ux_nexus_accounts_name
                            ON nexus_accounts (player_name COLLATE NOCASE)
                            """);
                }
            }
            case MARIADB -> {
                // MariaDB "CREATE UNIQUE INDEX IF NOT EXISTS" desteklemez; önce varlığı sorulur.
                if (!indexExists(connection, "nexus_accounts", "uk_nexus_accounts_name")) {
                    try (Statement statement = connection.createStatement()) {
                        statement.execute("ALTER TABLE nexus_accounts "
                                + "ADD UNIQUE KEY uk_nexus_accounts_name (player_name)");
                    }
                }
            }
        }
    }

    private static boolean indexExists(Connection connection, String table, String index) throws SQLException {
        String sql = "SELECT COUNT(*) FROM information_schema.statistics "
                + "WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, table);
            statement.setString(2, index);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && result.getInt(1) > 0;
            }
        }
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

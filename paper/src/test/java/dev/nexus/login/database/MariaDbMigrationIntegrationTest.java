package dev.nexus.login.database;

import dev.nexus.login.account.SqlAccountRepository;
import dev.nexus.login.config.DatabaseSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Gerçek MariaDB'ye karşı şema/migration testleri. Yalnızca NEXUS_TEST_MARIADB_PASSWORD
 * verildiğinde çalışır (yerel/CI); aksi halde atlanır. Veritabanındaki nexus_* tabloları SİLİNİR,
 * bu yüzden yalnızca boş bir test veritabanına yönlendirin.
 */
@EnabledIfEnvironmentVariable(named = "NEXUS_TEST_MARIADB_PASSWORD", matches = ".+")
class MariaDbMigrationIntegrationTest {

    private static final Logger LOGGER = Logger.getLogger("NexusLoginMariaDbTest");
    private static final Executor DIRECT = Runnable::run;
    private static final String HOST = env("NEXUS_TEST_MARIADB_HOST", "127.0.0.1");
    private static final int PORT = Integer.parseInt(env("NEXUS_TEST_MARIADB_PORT", "3307"));
    private static final String DB = env("NEXUS_TEST_MARIADB_DB", "nexus");
    private static final String USER = env("NEXUS_TEST_MARIADB_USER", "nexus");
    private static final String PASSWORD = System.getenv("NEXUS_TEST_MARIADB_PASSWORD");

    @TempDir
    Path tempDir;

    private DatabaseManager manager;

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static Connection raw() throws Exception {
        return DriverManager.getConnection("jdbc:mariadb://" + HOST + ":" + PORT + "/" + DB + "?sslMode=disable", USER, PASSWORD);
    }

    @BeforeEach
    void dropTables() throws Exception {
        try (Connection c = raw(); Statement s = c.createStatement()) {
            List<String> tables = new ArrayList<>();
            try (ResultSet rs = s.executeQuery("SHOW TABLES LIKE 'nexus\\_%'")) {
                while (rs.next()) {
                    tables.add(rs.getString(1));
                }
            }
            for (String table : tables) {
                s.execute("DROP TABLE `" + table + "`");
            }
        }
    }

    @AfterEach
    void close() {
        if (manager != null) {
            manager.close();
        }
    }

    private DatabaseManager start() {
        DatabaseSettings settings = new DatabaseSettings(DatabaseType.MARIADB, "unused.db",
                HOST, PORT, DB, USER, PASSWORD, "disable", 4, 1, 5_000L, 1_800_000L);
        DatabaseManager m = new DatabaseManager(settings, tempDir, DIRECT, LOGGER);
        m.start().join();
        return m;
    }

    private static int version(Statement s) throws Exception {
        try (ResultSet rs = s.executeQuery("SELECT meta_value FROM nexus_meta WHERE meta_key = '" + Schema.VERSION_KEY + "'")) {
            assertTrue(rs.next(), "şema sürümü yazılmış olmalı");
            return Integer.parseInt(rs.getString(1));
        }
    }

    private static Set<String> uniqueIndexes(Statement s, String table) throws Exception {
        Set<String> names = new HashSet<>();
        try (ResultSet rs = s.executeQuery("SHOW INDEX FROM `" + table + "`")) {
            while (rs.next()) {
                if (rs.getInt("Non_unique") == 0) {
                    names.add(rs.getString("Key_name") + ":" + rs.getString("Column_name"));
                }
            }
        }
        return names;
    }

    private static int count(Statement s, String table) throws Exception {
        try (ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM `" + table + "`")) {
            rs.next();
            return rs.getInt(1);
        }
    }

    @Test
    void freshDatabaseGetsSchemaV2WithUniqueName() throws Exception {
        manager = start();
        try (Connection c = raw(); Statement s = c.createStatement()) {
            assertEquals(Schema.VERSION, version(s));
            assertTrue(uniqueIndexes(s, "nexus_accounts").stream().anyMatch(i -> i.endsWith(":player_name")),
                    "player_name unique indeksi olmalı: " + uniqueIndexes(s, "nexus_accounts"));
        }
    }

    @Test
    void v1DatabaseIsBackedUpWithKeysThenDeduped() throws Exception {
        try (Connection c = raw(); Statement s = c.createStatement()) {
            for (String sql : Schema.statements(DatabaseType.MARIADB)) {
                s.execute(sql);
            }
            s.executeUpdate("INSERT INTO nexus_meta (meta_key, meta_value) VALUES ('" + Schema.VERSION_KEY + "', '1')");
            s.executeUpdate("INSERT INTO nexus_accounts (id, uuid, player_name, password_hash, is_premium) VALUES "
                    + "(1, '" + UUID.randomUUID() + "', 'alice', 'h', 0),"
                    + "(2, '" + UUID.randomUUID() + "', 'Alice', 'h', 0),"
                    + "(3, '" + UUID.randomUUID() + "', 'bob', 'h', 0),"
                    + "(4, '" + UUID.randomUUID() + "', 'ALICE', 'h', 0)");
        }

        manager = start();

        try (Connection c = raw(); Statement s = c.createStatement()) {
            assertEquals(Schema.VERSION, version(s));
            List<String> names = new ArrayList<>();
            try (ResultSet rs = s.executeQuery("SELECT player_name, id FROM nexus_accounts ORDER BY id")) {
                while (rs.next()) {
                    names.add(rs.getString(1) + "#" + rs.getLong(2));
                }
            }
            assertEquals(List.of("alice#1", "bob#3"), names, "yalnızca en eski satır kalmalı");

            assertEquals(4, count(s, "nexus_accounts_backup_v1"), "yedek dedupe öncesi 4 satırı içermeli");
            Set<String> backupKeys = uniqueIndexes(s, "nexus_accounts_backup_v1");
            assertTrue(backupKeys.contains("PRIMARY:id"), "yedek birincil anahtarı korumalı: " + backupKeys);
            assertTrue(backupKeys.stream().anyMatch(k -> k.endsWith(":uuid")), "yedek uuid unique anahtarını korumalı: " + backupKeys);
        }

        // İkinci açılış: migration tekrar çalışmaz, yedek bozulmaz.
        manager.close();
        manager = start();
        try (Connection c = raw(); Statement s = c.createStatement()) {
            assertEquals(Schema.VERSION, version(s));
            assertEquals(4, count(s, "nexus_accounts_backup_v1"));
        }
    }

    @Test
    void releaseNameAndDeleteWorkOnMariaDb() {
        manager = start();
        SqlAccountRepository accounts = new SqlAccountRepository(manager, DIRECT);
        UUID oldHolder = UUID.randomUUID();
        UUID newHolder = UUID.randomUUID();
        accounts.create(oldHolder, "Steve", "hash1", false, "127.0.0.1").join();

        assertEquals(1, accounts.releaseName("steve", newHolder).join());
        accounts.create(newHolder, "Steve", "hash2", false, "127.0.0.1").join();
        assertEquals("Steve", accounts.findByUuid(newHolder).join().orElseThrow().playerName());
        assertTrue(accounts.findByUuid(oldHolder).join().isPresent());
        assertTrue(accounts.delete(newHolder).join());
        assertTrue(accounts.findByUuid(newHolder).join().isEmpty());
    }
}

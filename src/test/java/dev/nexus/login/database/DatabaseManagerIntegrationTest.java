package dev.nexus.login.database;

import dev.nexus.login.account.Account;
import dev.nexus.login.account.SqlAccountRepository;
import dev.nexus.login.config.DatabaseSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Gerçek SQLite dosyası üzerinde şema kurulumu ve v1 → v2 migration testleri.
 *
 * <p>Bu testler özellikle elle denenen senaryoları kapsar: eski veritabanında yinelenen
 * isimler, unique indeksin gerçekten zorlanması, ileri şema sürümünün reddedilmesi ve
 * SQLite yolunun eklenti klasöründen dışarı çıkamaması.
 */
class DatabaseManagerIntegrationTest {

    private static final Logger LOGGER = Logger.getLogger("NexusLoginTest");
    private static final Executor DIRECT = r -> r.run();

    @TempDir
    Path tempDir;

    private DatabaseManager manager;

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.close();
        }
    }

    @Test
    void freshDatabaseGetsSchemaV2AndUniqueCaseInsensitiveNames() throws Exception {
        manager = start("nexus.db");

        assertEquals(Schema.VERSION, readVersion(manager), "şema sürümü v2 olmalı");
        assertTrue(uniqueNameIndexExists(manager), "unique isim indeksi oluşturulmalı");

        SqlAccountRepository accounts = new SqlAccountRepository(manager, DIRECT);
        accounts.create(UUID.randomUUID(), "Steve", "hash1", false, "127.0.0.1").join();

        // Aynı isim, farklı büyük/küçük harf: veritabanı bunu reddetmeli.
        CompletionException duplicate = assertThrows(CompletionException.class,
                () -> accounts.create(UUID.randomUUID(), "sTeVe", "hash2", false, "127.0.0.1").join());
        assertInstanceOf(DataAccessException.class, duplicate.getCause());

        // findByName büyük/küçük harf duyarsız olmalı (offline UUID casusluğunu engeller).
        Account found = accounts.findByName("steve").join().orElseThrow();
        assertEquals("Steve", found.playerName());
    }

    @Test
    void v1DatabaseWithDuplicateNamesIsDedupedKeepingOldestRow() throws Exception {
        // DatabaseManager dosyayı eklenti klasörü altında arar; eski DB'yi oraya kur.
        createLegacyV1Database(tempDir.resolve("plugin").resolve("legacy.db"));

        manager = start("legacy.db");

        assertEquals(Schema.VERSION, readVersion(manager), "migration sonrası sürüm v2 olmalı");
        assertTrue(uniqueNameIndexExists(manager), "unique isim indeksi oluşturulmalı");

        List<String> names = new ArrayList<>();
        try (Connection connection = manager.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT player_name, id FROM nexus_accounts ORDER BY id")) {
            while (rows.next()) {
                names.add(rows.getString(1) + "#" + rows.getLong(2));
            }
        }
        // 1=alice, 2=Alice, 3=bob, 4=ALICE → yalnızca en küçük id (1) ve bob (3) kalır.
        assertEquals(List.of("alice#1", "bob#3"), names);

        try (Connection connection = manager.getConnection();
             Statement statement = connection.createStatement()) {
            assertThrows(SQLException.class, () -> statement.executeUpdate(
                    "INSERT INTO nexus_accounts (uuid, player_name, is_premium) "
                            + "VALUES ('" + UUID.randomUUID() + "', 'ALICE', 0)"),
                    "dedupe sonrası aynı isim tekrar eklenememeli");
        }
    }

    @Test
    void refusesToStartWhenDatabaseSchemaIsNewer() throws Exception {
        Path file = tempDir.resolve("plugin").resolve("future.db");
        createLegacyV1Database(file);
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file);
             PreparedStatement statement = connection.prepareStatement(
                     "UPDATE nexus_meta SET meta_value = ? WHERE meta_key = ?")) {
            statement.setString(1, Integer.toString(Schema.VERSION + 5));
            statement.setString(2, Schema.VERSION_KEY);
            statement.executeUpdate();
        }

        CompletionException error = assertThrows(CompletionException.class,
                () -> start("future.db"));
        assertInstanceOf(DataAccessException.class, error.getCause());
        assertTrue(String.valueOf(error.getCause().getMessage()).contains("daha yeni"),
                "hata mesajı sürüm uyuşmazlığını açıklamalı: " + error.getCause().getMessage());
    }

    @Test
    void rejectsSqlitePathThatEscapesPluginFolder() {
        DatabaseSettings settings = new DatabaseSettings(DatabaseType.SQLITE, "../disari.db",
                "localhost", 3306, "nexus", "nexus", "", "disable",
                10, 2, 5_000L, 1_800_000L);
        manager = new DatabaseManager(settings, tempDir.resolve("plugin"), DIRECT, LOGGER);

        CompletionException error = assertThrows(CompletionException.class, () -> manager.start().join());
        assertInstanceOf(DataAccessException.class, error.getCause());
        assertFalse(Files.exists(tempDir.resolve("disari.db")),
                "eklenti klasörü dışına dosya yazılmamalı");
    }

    private DatabaseManager start(String sqliteFile) {
        DatabaseSettings settings = new DatabaseSettings(DatabaseType.SQLITE, sqliteFile,
                "localhost", 3306, "nexus", "nexus", "", "disable",
                10, 2, 5_000L, 1_800_000L);
        DatabaseManager created = new DatabaseManager(settings, tempDir.resolve("plugin"), DIRECT, LOGGER);
        created.start().join();
        return created;
    }

    private static int readVersion(DatabaseManager manager) throws SQLException {
        try (Connection connection = manager.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT meta_value FROM nexus_meta WHERE meta_key = ?")) {
            statement.setString(1, Schema.VERSION_KEY);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next(), "schema_version satırı olmalı");
                return Integer.parseInt(result.getString(1));
            }
        }
    }

    private static boolean uniqueNameIndexExists(DatabaseManager manager) throws SQLException {
        try (Connection connection = manager.getConnection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("PRAGMA index_list(nexus_accounts)")) {
            while (result.next()) {
                if ("ux_nexus_accounts_name".equals(result.getString("name"))) {
                    return result.getInt("unique") == 1;
                }
            }
            return false;
        }
    }

    /** v1 şemasını (isim indeksi olmadan) kurar ve yinelenen isimli satırlar ekler. */
    private static void createLegacyV1Database(Path file) throws Exception {
        Files.createDirectories(file.getParent());
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + file)) {
            try (Statement statement = connection.createStatement()) {
                for (String sql : Schema.statements(DatabaseType.SQLITE)) {
                    statement.execute(sql);
                }
                statement.executeUpdate("INSERT INTO nexus_meta (meta_key, meta_value) "
                        + "VALUES ('" + Schema.VERSION_KEY + "', '1')");
                statement.executeUpdate("INSERT INTO nexus_accounts "
                        + "(id, uuid, player_name, password_hash, is_premium) VALUES "
                        + "(1, '" + UUID.randomUUID() + "', 'alice', 'h', 0),"
                        + "(2, '" + UUID.randomUUID() + "', 'Alice', 'h', 0),"
                        + "(3, '" + UUID.randomUUID() + "', 'bob',   'h', 0),"
                        + "(4, '" + UUID.randomUUID() + "', 'ALICE', 'h', 0)");
            }
        }
    }
}

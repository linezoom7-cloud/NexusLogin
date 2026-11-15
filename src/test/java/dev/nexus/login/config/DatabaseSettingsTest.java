package dev.nexus.login.config;

import dev.nexus.login.database.DatabaseType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** MariaDB TLS kipinin doğrulanması ve şifrenin loglara sızmaması. */
class DatabaseSettingsTest {

    private static DatabaseSettings mariadb(String sslMode) {
        return new DatabaseSettings(DatabaseType.MARIADB, "nexus.db",
                "db.example.com", 3306, "nexus", "nexus", "gizli-sifre", sslMode,
                10, 2, 5_000L, 1_800_000L);
    }

    @Test
    void acceptsSupportedSslModesCaseInsensitively() {
        assertEquals("disable", mariadb("disable").sslMode());
        assertEquals("trust", mariadb("TRUST").sslMode());
        assertEquals("verify-ca", mariadb(" verify-ca ").sslMode());
        assertEquals("verify-full", mariadb("Verify-Full").sslMode());
    }

    @Test
    void rejectsUnknownOrEmptySslModeForMariaDb() {
        for (String mode : new String[]{"", "required", "preferred", "true", "false", "verify_ca"}) {
            assertThrows(IllegalArgumentException.class, () -> mariadb(mode),
                    "geçersiz ssl-mode kabul edilmemeli: '" + mode + "'");
        }
        assertThrows(IllegalArgumentException.class, () -> mariadb(null));
    }

    @Test
    void sqliteIgnoresSslMode() {
        DatabaseSettings settings = new DatabaseSettings(DatabaseType.SQLITE, "nexus.db",
                "localhost", 3306, "nexus", "nexus", "", "whatever",
                10, 2, 5_000L, 1_800_000L);
        assertEquals("whatever", settings.sslMode());
    }

    @Test
    void toStringNeverLeaksPassword() {
        String text = mariadb("verify-full").toString();
        assertFalse(text.contains("gizli-sifre"), "şifre toString'e sızmamalı: " + text);
        assertTrue(text.contains("password=***"));
        assertTrue(text.contains("verify-full"));
    }

    @Test
    void rejectsJdbcUrlParameterInjection() {
        assertThrows(IllegalArgumentException.class, () -> new DatabaseSettings(DatabaseType.MARIADB, "nexus.db",
                "db.example.com", 3306, "nexus?allowLocalInfile=true", "nexus", "", "disable",
                10, 2, 5_000L, 1_800_000L));
        assertThrows(IllegalArgumentException.class, () -> new DatabaseSettings(DatabaseType.MARIADB, "nexus.db",
                "evil.com/x?a=b", 3306, "nexus", "nexus", "", "disable",
                10, 2, 5_000L, 1_800_000L));
        assertDoesNotThrow(() -> new DatabaseSettings(DatabaseType.MARIADB, "nexus.db",
                "[::1]", 3306, "nexus_db", "nexus", "", "disable", 10, 2, 5_000L, 1_800_000L));
    }

    @Test
    void validatesPoolBounds() {
        assertThrows(IllegalArgumentException.class, () -> new DatabaseSettings(
                DatabaseType.MARIADB, "nexus.db", "localhost", 3306, "nexus", "nexus", "",
                "disable", 0, 0, 5_000L, 1_800_000L));
        assertThrows(IllegalArgumentException.class, () -> new DatabaseSettings(
                DatabaseType.MARIADB, "nexus.db", "localhost", 3306, "nexus", "nexus", "",
                "disable", 5, 6, 5_000L, 1_800_000L));
        assertThrows(IllegalArgumentException.class, () -> new DatabaseSettings(
                DatabaseType.MARIADB, "nexus.db", "localhost", 0, "nexus", "nexus", "",
                "disable", 5, 0, 5_000L, 1_800_000L));
    }
}

package dev.nexus.login.database;

import java.util.List;

/**
 * Lehçeye (dialect) özel DDL. DML sorguları her iki veritabanında da aynı olduğu için
 * repository katmanında ortaktır; yalnızca tablo oluşturma burada ayrışır.
 */
public final class Schema {

    /**
     * Beklenen şema sürümü. {@code nexus_meta} tablosundaki {@code schema_version}
     * değeriyle karşılaştırılır. Tablo yapısı değiştiğinde bu sayı artırılmalı ve
     * {@code DatabaseManager.applyMigrations} içine ilgili adım eklenmelidir.
     */
    public static final int VERSION = 3;

    /** {@code nexus_meta} tablosunda şema sürümünün tutulduğu anahtar. */
    public static final String VERSION_KEY = "schema_version";

    private Schema() {
    }

    public static List<String> statements(DatabaseType type) {
        return switch (type) {
            case SQLITE -> List.of(
                    """
                    CREATE TABLE IF NOT EXISTS nexus_meta (
                        meta_key      VARCHAR(64)  PRIMARY KEY,
                        meta_value    VARCHAR(255) NOT NULL
                    )
                    """,
                    """
                    CREATE TABLE IF NOT EXISTS nexus_accounts (
                        id            INTEGER PRIMARY KEY AUTOINCREMENT,
                        uuid          CHAR(36)     NOT NULL UNIQUE,
                        player_name   VARCHAR(16)  NOT NULL COLLATE NOCASE,
                        password_hash VARCHAR(255) NULL,
                        is_premium    BOOLEAN      NOT NULL DEFAULT 0,
                        reg_ip        VARCHAR(45)  NULL,
                        last_ip       VARCHAR(45)  NULL
                    )
                    """,
                    // İsim tekilliği v2 migration'ında (dedupe sonrası) oluşturulur; burada
                    // non-unique indeks yaratmak eski veritabanlarında çakışmayı erken tetiklerdi.
                    "CREATE INDEX IF NOT EXISTS idx_nexus_accounts_reg_ip ON nexus_accounts (reg_ip)",
                    """
                    CREATE TABLE IF NOT EXISTS nexus_login_attempts (
                        attempt_key   VARCHAR(64) PRIMARY KEY,
                        attempts      INTEGER     NOT NULL DEFAULT 0,
                        window_start  BIGINT      NOT NULL,
                        locked_until  BIGINT      NOT NULL DEFAULT 0,
                        block_count   INTEGER     NOT NULL DEFAULT 0,
                        updated_at    BIGINT      NOT NULL
                    )
                    """,
                    // v3: iki adımlı doğrulama. Satır varsa 2FA etkindir (yarım kayıt DB'ye yazılmaz).
                    """
                    CREATE TABLE IF NOT EXISTS nexus_totp (
                        uuid          CHAR(36)     PRIMARY KEY,
                        secret_enc    VARCHAR(160) NOT NULL,
                        last_step     BIGINT       NOT NULL DEFAULT 0,
                        created_at    BIGINT       NOT NULL
                    )
                    """
            );
            case MARIADB -> List.of(
                    """
                    CREATE TABLE IF NOT EXISTS nexus_meta (
                        meta_key      VARCHAR(64)  NOT NULL,
                        meta_value    VARCHAR(255) NOT NULL,
                        PRIMARY KEY (meta_key)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """,
                    """
                    CREATE TABLE IF NOT EXISTS nexus_accounts (
                        id            INT UNSIGNED NOT NULL AUTO_INCREMENT,
                        uuid          CHAR(36)     NOT NULL,
                        player_name   VARCHAR(16)  NOT NULL,
                        password_hash VARCHAR(255) NULL,
                        is_premium    BOOLEAN      NOT NULL DEFAULT FALSE,
                        reg_ip        VARCHAR(45)  NULL,
                        last_ip       VARCHAR(45)  NULL,
                        PRIMARY KEY (id),
                        UNIQUE KEY uk_nexus_accounts_uuid (uuid),
                        KEY idx_nexus_accounts_reg_ip (reg_ip)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """,
                    """
                    CREATE TABLE IF NOT EXISTS nexus_login_attempts (
                        attempt_key   VARCHAR(64) NOT NULL,
                        attempts      INT         NOT NULL DEFAULT 0,
                        window_start  BIGINT      NOT NULL,
                        locked_until  BIGINT      NOT NULL DEFAULT 0,
                        block_count   INT         NOT NULL DEFAULT 0,
                        updated_at    BIGINT      NOT NULL,
                        PRIMARY KEY (attempt_key),
                        KEY idx_nexus_attempts_updated (updated_at)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """,
                    // v3: iki adımlı doğrulama. Satır varsa 2FA etkindir (yarım kayıt DB'ye yazılmaz).
                    """
                    CREATE TABLE IF NOT EXISTS nexus_totp (
                        uuid          CHAR(36)     NOT NULL,
                        secret_enc    VARCHAR(160) NOT NULL,
                        last_step     BIGINT       NOT NULL DEFAULT 0,
                        created_at    BIGINT       NOT NULL,
                        PRIMARY KEY (uuid)
                    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                    """
            );
        };
    }
}

package dev.nexus.login.config;

import org.bukkit.configuration.ConfigurationSection;

/**
 * Argon2id maliyet parametreleri.
 *
 * @param memoryKib     hash başına bellek (KiB)
 * @param iterations    zaman maliyeti
 * @param parallelism   şerit sayısı
 * @param saltLength    rastgele salt uzunluğu (byte)
 * @param hashLength    çıktı uzunluğu (byte)
 * @param maxConcurrent aynı anda çalışabilecek hash sayısı (bellek tavanını belirler)
 */
public record HashingSettings(
        int memoryKib,
        int iterations,
        int parallelism,
        int saltLength,
        int hashLength,
        int maxConcurrent
) {

    /** OWASP Password Storage Cheat Sheet: Argon2id için önerilen en düşük bellek (19 MiB). */
    public static final int OWASP_MIN_MEMORY_KIB = 19_456;

    public HashingSettings {
        if (memoryKib < OWASP_MIN_MEMORY_KIB) {
            throw new IllegalArgumentException(
                    "security.argon2.memory-kib en az " + OWASP_MIN_MEMORY_KIB + " olmalı (OWASP alt sınırı)");
        }
        if (iterations < 1) {
            throw new IllegalArgumentException("security.argon2.iterations >= 1 olmalı");
        }
        if (parallelism < 1 || memoryKib < 8 * parallelism) {
            throw new IllegalArgumentException("security.argon2.parallelism >= 1 ve memory-kib >= 8 x parallelism olmalı");
        }
        if (saltLength < 16) {
            throw new IllegalArgumentException("security.argon2.salt-length en az 16 byte olmalı");
        }
        if (hashLength < 32) {
            throw new IllegalArgumentException("security.argon2.hash-length en az 32 byte olmalı");
        }
        if (maxConcurrent < 1) {
            throw new IllegalArgumentException("security.argon2.max-concurrent >= 1 olmalı");
        }
    }

    static HashingSettings from(ConfigurationSection s) {
        return new HashingSettings(
                s.getInt("memory-kib", 65_536),
                s.getInt("iterations", 3),
                s.getInt("parallelism", 1),
                s.getInt("salt-length", 16),
                s.getInt("hash-length", 32),
                s.getInt("max-concurrent", 2)
        );
    }
}

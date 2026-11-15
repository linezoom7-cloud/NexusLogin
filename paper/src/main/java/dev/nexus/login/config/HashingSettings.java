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
 * @param pepper        sunucu tarafı gizli anahtar; boş değilse PIN, Argon2'den önce
 *                      HMAC-SHA256 ile bağlanır. Veritabanı sızsa bile pepper olmadan
 *                      hash'ler çevrimdışı kırılamaz. Değiştirmek eski PIN'leri geçersiz kılar.
 */
public record HashingSettings(
        int memoryKib,
        int iterations,
        int parallelism,
        int saltLength,
        int hashLength,
        int maxConcurrent,
        String pepper
) {

    /** OWASP Password Storage Cheat Sheet: Argon2id için önerilen en düşük bellek (19 MiB). */
    public static final int OWASP_MIN_MEMORY_KIB = 19_456;

    /** Pepper için kabul edilen en kısa uzunluk (karakter). */
    public static final int MIN_PEPPER_LENGTH = 16;

    /** Testler ve eski çağrılar için pepper'sız uyumluluk kurucusu. */
    public HashingSettings(int memoryKib, int iterations, int parallelism,
                           int saltLength, int hashLength, int maxConcurrent) {
        this(memoryKib, iterations, parallelism, saltLength, hashLength, maxConcurrent, "");
    }

    public HashingSettings {
        pepper = pepper == null ? "" : pepper.trim();
        if (!pepper.isEmpty() && pepper.length() < MIN_PEPPER_LENGTH) {
            throw new IllegalArgumentException("security.argon2.pepper en az "
                    + MIN_PEPPER_LENGTH + " karakter olmalı (veya boş bırakılmalı)");
        }
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
                s.getInt("max-concurrent", 2),
                s.getString("pepper", "")
        );
    }

    /** Pepper bir sırttır: record'un otomatik toString'i onu loglara sızdırmamalıdır. */
    @Override
    public String toString() {
        return "HashingSettings[memoryKib=%d, iterations=%d, parallelism=%d, saltLength=%d, "
                .formatted(memoryKib, iterations, parallelism, saltLength)
                + "hashLength=%d, maxConcurrent=%d, pepper=%s]"
                .formatted(hashLength, maxConcurrent, pepper.isEmpty() ? "off" : "***");
    }
}

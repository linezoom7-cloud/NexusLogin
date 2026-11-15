package dev.nexus.login.config;

import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.Nullable;

/**
 * Denetim günlüğünün diskte sınırsız büyümesini engelleyen döndürme ayarları.
 *
 * <p>Dosya {@code max-size-kb} sınırına ulaştığında {@code nexus-auth.log.1},
 * {@code nexus-auth.log.2} … biçiminde yedeklenir; en fazla {@code keep-files} eski dosya
 * saklanır. Böylece yoğun bir saldırı sırasında bile disk dolmaz.
 *
 * @param maxSizeKb tek dosyanın üst sınırı (KiB); 0 = döndürme kapalı
 * @param keepFiles döndürme sonrası saklanan eski dosya sayısı
 */
public record AuditSettings(long maxSizeKb, int keepFiles) {

    public static final long DEFAULT_MAX_SIZE_KB = 1024;
    public static final int DEFAULT_KEEP_FILES = 5;
    /** 1 GiB: tek bir denetim dosyası için makul tavan. */
    public static final long MAX_SIZE_KB_LIMIT = 1_048_576;

    public AuditSettings {
        // Üst sınır: maxSizeKb * 1024 long'da taşıp negatif olsaydı döndürme sessizce kapanırdı.
        if (maxSizeKb < 0 || maxSizeKb > MAX_SIZE_KB_LIMIT) {
            throw new IllegalArgumentException("security.audit.max-size-kb 0-" + MAX_SIZE_KB_LIMIT + " aralığında olmalı");
        }
        if (keepFiles < 1 || keepFiles > 100) {
            throw new IllegalArgumentException("security.audit.keep-files 1-100 aralığında olmalı");
        }
    }

    /** 0 → döndürme kapalı. */
    public long maxSizeBytes() {
        return maxSizeKb == 0 ? 0 : maxSizeKb * 1024L;
    }

    static AuditSettings from(@Nullable ConfigurationSection section) {
        if (section == null) {
            return new AuditSettings(DEFAULT_MAX_SIZE_KB, DEFAULT_KEEP_FILES);
        }
        return new AuditSettings(
                section.getLong("max-size-kb", DEFAULT_MAX_SIZE_KB),
                section.getInt("keep-files", DEFAULT_KEEP_FILES)
        );
    }
}

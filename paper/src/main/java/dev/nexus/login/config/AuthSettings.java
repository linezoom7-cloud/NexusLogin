package dev.nexus.login.config;

import org.bukkit.configuration.ConfigurationSection;

import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Giriş akışı kuralları.
 *
 * @param timeout          oyuncunun giriş yapmak için sahip olduğu süre
 * @param maxAccountsPerIp aynı IP'den açılabilecek hesap sayısı (0 = sınırsız)
 * @param rememberFor      aynı IP'den bu süre içinde dönen oyuncu otomatik girer (0 = kapalı)
 * @param allowedCommands         giriş yapmamış oyuncunun kullanabileceği komutlar
 * @param premiumAutoLogin        yalnızca sunucu online-mode=true iken etkinleşir
 * @param teleportToSpawn         giriş yapana kadar oyuncuyu dünya spawn'ına ışınlar
 * @param restorePreviousLocation başarılı giriş sonrası oyuncuyu çıktığı eski konumuna döndürür
 * @param hideInventory           giriş yapana kadar envanterdeki eşyaları geçici olarak gizler
 * @param throttle                kalıcı kaba kuvvet koruması ayarları
 */
public record AuthSettings(
        Duration timeout,
        int maxAccountsPerIp,
        Duration rememberFor,
        int pinMinLength,
        int pinMaxLength,
        Set<String> allowedCommands,
        boolean premiumAutoLogin,
        boolean teleportToSpawn,
        boolean restorePreviousLocation,
        boolean hideInventory,
        ThrottleSettings throttle
) {

    /** PIN göstergesi menünün tek satırına (9 slot) sığmak zorunda. */
    public static final int PIN_HARD_MAX = 9;

    public AuthSettings {
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(rememberFor, "rememberFor");
        Objects.requireNonNull(throttle, "throttle");
        // Üst sınır 1 saat: daha uzunu kısıtlı (ölümsüz, kör) hayalet oyuncuları süresiz tutar ve
        // aşırı büyük değerler Instant hesabında taşma (DateTimeException) üretirdi.
        if (timeout.toSeconds() < 15 || timeout.toSeconds() > 3_600) {
            throw new IllegalArgumentException("auth.timeout-seconds 15-3600 aralığında olmalı");
        }
        if (rememberFor.isNegative() || rememberFor.toDays() > 30) {
            throw new IllegalArgumentException("auth.session-remember-minutes 0-43200 (30 gün) aralığında olmalı");
        }
        if (maxAccountsPerIp < 0) {
            throw new IllegalArgumentException("auth.max-accounts-per-ip >= 0 olmalı");
        }
        if (pinMinLength < 4 || pinMaxLength > PIN_HARD_MAX || pinMinLength > pinMaxLength) {
            throw new IllegalArgumentException("auth.pin: 4 <= min-length <= max-length <= " + PIN_HARD_MAX + " olmalı");
        }
        allowedCommands = Set.copyOf(allowedCommands);
    }

    static AuthSettings from(ConfigurationSection s) {
        return new AuthSettings(
                Duration.ofSeconds(s.getLong("timeout-seconds", 120)),
                s.getInt("max-accounts-per-ip", 3),
                // Güvenli varsayılan: kapalı. IP+UUID tabanlı "beni hatırla", relay veya
                // offline-mode arkasında PIN'i tamamen atlatabildiği için açıkça istenmeli.
                Duration.ofMinutes(Math.max(0, s.getLong("session-remember-minutes", 0))),
                // 6 hane yalnızca 1.000.000 olasılıktır; veritabanı sızarsa saatler içinde kırılır.
                s.getInt("pin.min-length", 8),
                s.getInt("pin.max-length", 9),
                s.getStringList("allowed-commands").stream()
                        .map(c -> c.toLowerCase(Locale.ROOT).replace("/", ""))
                        .collect(Collectors.toSet()),
                // İkinci faktörü kaldırdığı için varsayılan kapalı; çalınmış bir Mojang
                // oturumu aksi halde doğrudan erişim demek olurdu.
                s.getBoolean("premium-auto-login", false),
                s.getBoolean("teleport-to-spawn", true),
                s.getBoolean("restore-previous-location", true),
                s.getBoolean("hide-inventory", true),
                ThrottleSettings.from(s)
        );
    }
}

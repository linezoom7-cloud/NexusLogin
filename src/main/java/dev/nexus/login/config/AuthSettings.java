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
        if (timeout.toSeconds() < 15) {
            throw new IllegalArgumentException("auth.timeout-seconds en az 15 olmalı");
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
                Duration.ofMinutes(Math.max(0, s.getLong("session-remember-minutes", 10))),
                // 6 hane yalnızca 1.000.000 olasılıktır; veritabanı sızarsa saatler içinde kırılır.
                s.getInt("pin.min-length", 8),
                s.getInt("pin.max-length", 9),
                s.getStringList("allowed-commands").stream()
                        .map(c -> c.toLowerCase(Locale.ROOT).replace("/", ""))
                        .collect(Collectors.toSet()),
                s.getBoolean("premium-auto-login", true),
                s.getBoolean("teleport-to-spawn", false),
                s.getBoolean("restore-previous-location", true),
                s.getBoolean("hide-inventory", true),
                ThrottleSettings.from(s)
        );
    }
}

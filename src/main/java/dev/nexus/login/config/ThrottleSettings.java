package dev.nexus.login.config;

import org.bukkit.configuration.ConfigurationSection;

import java.time.Duration;

/**
 * Kalıcı kaba kuvvet koruması ayarları.
 *
 * <p>İki sayaç boyutu vardır: <b>hesap</b> (UUID) ve <b>IP</b>. Hesap sayacı her zaman
 * açıktır ve asıl savunmadır; yeniden bağlanmak onu sıfırlamaz. IP sayacı ise yalnızca
 * gerçek istemci IP'si görünüyorsa anlamlıdır — MCSH gibi aktarıcı/proxy arkalarında
 * herkes aynı IP'de göründüğü için varsayılan olarak kapalıdır.
 *
 * @param window           hatalı denemelerin sayıldığı kayan pencere
 * @param accountThreshold aynı hesap için pencere başına izin verilen hatalı deneme (>= 1)
 * @param ipThreshold      aynı IP için pencere başına izin verilen hatalı deneme (0 = kapalı)
 * @param baseLockout      ilk kilidin süresi; her yeni kilitte ikiye katlanır
 * @param maxLockout       üstel cezanın tavanı
 */
public record ThrottleSettings(
        Duration window,
        int accountThreshold,
        int ipThreshold,
        Duration baseLockout,
        Duration maxLockout
) {

    public ThrottleSettings {
        if (window.toSeconds() < 60) {
            throw new IllegalArgumentException("auth.throttle.window-minutes en az 1 olmalı");
        }
        if (accountThreshold < 1) {
            throw new IllegalArgumentException("auth.throttle.account-threshold >= 1 olmalı");
        }
        if (ipThreshold < 0) {
            throw new IllegalArgumentException("auth.throttle.ip-threshold >= 0 olmalı");
        }
        if (baseLockout.toSeconds() < 5) {
            throw new IllegalArgumentException("auth.throttle.base-lockout-minutes en az 1 olmalı");
        }
        if (maxLockout.compareTo(baseLockout) < 0) {
            throw new IllegalArgumentException("auth.throttle.max-lockout-minutes >= base-lockout-minutes olmalı");
        }
        // 1 yıl tavan: daha büyük değerler ms'ye çevrilirken taşabilir (ArithmeticException).
        if (maxLockout.toDays() > 365 || window.toDays() > 365) {
            throw new IllegalArgumentException("auth.throttle süreleri en fazla 365 gün olabilir");
        }
    }

    public boolean ipEnabled() {
        return ipThreshold > 0;
    }

    /**
     * {@code throttle} bölümü yoksa eski {@code max-attempts} / {@code lockout-minutes}
     * anahtarları geriye dönük uyumluluk için kullanılır; böylece mevcut kurulumlar
     * güncellemeden sonra da korunmaya devam eder.
     */
    static ThrottleSettings from(ConfigurationSection auth) {
        ConfigurationSection throttle = auth.getConfigurationSection("throttle");
        if (throttle == null) {
            return new ThrottleSettings(
                    Duration.ofMinutes(15),
                    Math.max(1, auth.getInt("max-attempts", 5)),
                    0,
                    Duration.ofMinutes(Math.max(1, auth.getLong("lockout-minutes", 5))),
                    Duration.ofMinutes(24 * 60));
        }
        return new ThrottleSettings(
                Duration.ofMinutes(throttle.getLong("window-minutes", 15)),
                throttle.getInt("account-threshold", 5),
                throttle.getInt("ip-threshold", 0),
                Duration.ofMinutes(throttle.getLong("base-lockout-minutes", 1)),
                Duration.ofMinutes(throttle.getLong("max-lockout-minutes", 24 * 60)));
    }
}

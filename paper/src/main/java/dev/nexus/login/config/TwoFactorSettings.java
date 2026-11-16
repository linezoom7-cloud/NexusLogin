package dev.nexus.login.config;

import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.Nullable;

/**
 * {@code two-factor:} bölümü. Varsayılan kapalı.
 *
 * @param issuer doğrulayıcı uygulamada görünen ad; ':' içeremez (otpauth etiket ayırıcısı)
 */
public record TwoFactorSettings(boolean enabled, String issuer) {

    public TwoFactorSettings {
        issuer = issuer == null ? "" : issuer.strip();
        if (enabled && (issuer.isEmpty() || issuer.length() > 32 || issuer.contains(":"))) {
            throw new IllegalArgumentException("two-factor.issuer 1-32 karakter olmalı ve ':' içermemeli");
        }
    }

    public static TwoFactorSettings from(@Nullable ConfigurationSection s) {
        if (s == null) {
            return new TwoFactorSettings(false, "NexusLogin");
        }
        return new TwoFactorSettings(s.getBoolean("enabled", false), s.getString("issuer", "NexusLogin"));
    }
}

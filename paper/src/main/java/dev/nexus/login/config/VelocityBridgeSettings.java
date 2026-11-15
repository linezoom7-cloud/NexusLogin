package dev.nexus.login.config;

import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.Nullable;

/**
 * {@code velocity:} bölümü. Kapalıyken (varsayılan) eklenti tek sunucu modunda çalışır.
 *
 * @param serverName bu sunucunun velocity.toml {@code [servers]} altındaki adı; imzaya girer,
 *                   proxy'deki adla birebir aynı olmalı
 */
public record VelocityBridgeSettings(boolean enabled, String serverName) {

    private static final java.util.regex.Pattern NAME = java.util.regex.Pattern.compile("[A-Za-z0-9_.-]{1,64}");

    public VelocityBridgeSettings {
        serverName = serverName == null ? "" : serverName.strip();
        if (enabled && !NAME.matcher(serverName).matches()) {
            throw new IllegalArgumentException("velocity.server-name geçersiz: '" + serverName
                    + "' (velocity.toml'daki sunucu adı; harf, rakam, _ . - ve en fazla 64 karakter)");
        }
    }

    public static VelocityBridgeSettings disabled() {
        return new VelocityBridgeSettings(false, "");
    }

    /** Eski config dosyalarında bölüm yoktur: kapalı kabul edilir. */
    public static VelocityBridgeSettings from(@Nullable ConfigurationSection s) {
        if (s == null) {
            return disabled();
        }
        return new VelocityBridgeSettings(s.getBoolean("enabled", false), s.getString("server-name", ""));
    }
}

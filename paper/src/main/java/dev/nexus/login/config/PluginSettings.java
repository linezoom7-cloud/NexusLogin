package dev.nexus.login.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.Objects;

/**
 * config.yml'nin değişmez (immutable), doğrulanmış temsili.
 * Bukkit'in mutable FileConfiguration nesnesi yalnızca burada okunur; geri kalan kod
 * sadece bu record'ları görür ve thread-safe biçimde paylaşabilir.
 */
public record PluginSettings(
        DatabaseSettings database,
        HashingSettings hashing,
        AuthSettings auth,
        PackSettings pack,
        AuditSettings audit,
        BrandingSettings branding,
        VelocityBridgeSettings velocity
) {

    public PluginSettings {
        Objects.requireNonNull(database, "database");
        Objects.requireNonNull(hashing, "hashing");
        Objects.requireNonNull(auth, "auth");
        Objects.requireNonNull(pack, "pack");
        Objects.requireNonNull(audit, "audit");
        Objects.requireNonNull(branding, "branding");
        Objects.requireNonNull(velocity, "velocity");
    }

    /**
     * @throws IllegalArgumentException herhangi bir değer geçersizse (mesaj, hatalı anahtarı içerir)
     */
    public static PluginSettings load(FileConfiguration config) {
        return new PluginSettings(
                DatabaseSettings.from(section(config, "database")),
                HashingSettings.from(section(config, "security.argon2")),
                AuthSettings.from(section(config, "auth")),
                PackSettings.from(section(config, "resource-pack")),
                // Eski config dosyalarında bu bölümler yok; varsayılanlarla devam et.
                AuditSettings.from(config.getConfigurationSection("security.audit")),
                BrandingSettings.from(config.getConfigurationSection("branding")),
                VelocityBridgeSettings.from(config.getConfigurationSection("velocity"))
        );
    }

    static ConfigurationSection section(ConfigurationSection parent, String path) {
        ConfigurationSection section = parent.getConfigurationSection(path);
        if (section == null) {
            throw new IllegalArgumentException("config.yml içinde '" + path + "' bölümü eksik");
        }
        return section;
    }
}

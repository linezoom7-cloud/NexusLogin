package dev.nexus.login.config;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * JAR ile gelen config.yml'nin gerçekten ayrıştırılabildiğini ve doğrulamadan geçtiğini
 * doğrular. Bozuk bir girinti veya aralık dışı bir değer, sunucuda eklentiyi açılmaz
 * hale getirirdi; bu test onu yayından önce yakalar.
 */
class ConfigFileTest {

    @Test
    void shippedConfigParsesAndLoadsWithDefaults() {
        YamlConfiguration config = load();
        PluginSettings settings = PluginSettings.load(config);

        assertTrue(settings.pack().enabled(), "varsayılan olarak paket açık olmalı");
        assertEquals(PackSettings.PackDelivery.SAME_PORT, settings.pack().delivery());
        assertEquals(8, settings.auth().pinMinLength());
        assertEquals(9, settings.auth().pinMaxLength());
        assertTrue(settings.auth().allowedCommands().contains("giris"));
        assertTrue(settings.auth().throttle().accountThreshold() >= 1);
        assertEquals(AuditSettings.DEFAULT_MAX_SIZE_KB, settings.audit().maxSizeKb());
        assertEquals(AuditSettings.DEFAULT_KEEP_FILES, settings.audit().keepFiles());
        assertNotNull(config.getConfigurationSection("security.audit"),
                "security.audit bölümü config.yml içinde bulunmalı");
        assertTrue(config.isString("language"), "language anahtarı eksik");
    }

    @Test
    void shippedDefaultsAreSecure() {
        PluginSettings settings = PluginSettings.load(load());

        assertTrue(settings.auth().rememberFor().isZero(),
                "varsayılan olarak 'beni hatırla' kapalı olmalı (IP+UUID modeli relay arkasında güvenli değil)");
        assertFalse(settings.auth().premiumAutoLogin(),
                "varsayılan olarak premium otomatik giriş kapalı olmalı");
        assertEquals("disable", settings.database().sslMode(),
                "varsayılan TLS kipi belgelenen değer olmalı");
        assertTrue(settings.hashing().pepper().isEmpty(),
                "varsayılan pepper boş olmalı (mevcut PIN'leri geçersiz kılmamak için)");
    }

    private static YamlConfiguration load() {
        try (InputStream in = ConfigFileTest.class.getResourceAsStream("/config.yml")) {
            assertNotNull(in, "config.yml kaynağı bulunamadı");
            YamlConfiguration config = new YamlConfiguration();
            config.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            return config;
        } catch (Exception e) {
            throw new AssertionError("config.yml okunamadı", e);
        }
    }
}

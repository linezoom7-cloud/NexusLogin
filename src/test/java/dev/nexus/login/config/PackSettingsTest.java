package dev.nexus.login.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PackSettingsTest {

    private static ConfigurationSection section(Object... keyValues) {
        MemoryConfiguration config = new MemoryConfiguration();
        config.createSection("resource-pack");
        for (int i = 0; i < keyValues.length; i += 2) {
            config.set("resource-pack." + keyValues[i], keyValues[i + 1]);
        }
        return config.getConfigurationSection("resource-pack");
    }

    @Test
    void defaultsToSamePortSoNoExtraPortIsNeeded() {
        PackSettings settings = PackSettings.from(section());
        assertTrue(settings.enabled());
        assertEquals(PackSettings.PackDelivery.SAME_PORT, settings.delivery());
        assertEquals(8164, settings.port());
        assertFalse(settings.required());
    }

    @Test
    void acceptsDeliveryAliases() {
        assertEquals(PackSettings.PackDelivery.SAME_PORT,
                PackSettings.from(section("delivery", "SAME_PORT")).delivery());
        assertEquals(PackSettings.PackDelivery.PORT,
                PackSettings.from(section("delivery", " port ")).delivery());
        assertEquals(PackSettings.PackDelivery.URL,
                PackSettings.from(section("delivery", "cdn", "public-url", "https://example.com/p.zip")).delivery());
    }

    @Test
    void rejectsUnknownDelivery() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> PackSettings.from(section("delivery", "ftp")));
        assertTrue(error.getMessage().contains("delivery"), error.getMessage());
    }

    @Test
    void urlDeliveryRequiresPublicUrl() {
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> PackSettings.from(section("delivery", "url")));
        assertTrue(error.getMessage().contains("public-url"), error.getMessage());
    }

    @Test
    void rejectsPortOutOfRange() {
        assertThrows(IllegalArgumentException.class,
                () -> new PackSettings(true, PackSettings.PackDelivery.PORT, 0, "", "", false));
        assertThrows(IllegalArgumentException.class,
                () -> new PackSettings(true, PackSettings.PackDelivery.PORT, 70_000, "", "", false));
    }

    @Test
    void disabledPackIgnoresInvalidPort() {
        // Paket kapalıyken port hiç kullanılmaz: eski/yanlış bir değer eklentiyi durdurmamalı.
        PackSettings settings = new PackSettings(false, PackSettings.PackDelivery.PORT, 0, "", "", false);
        assertFalse(settings.enabled());
        assertEquals(0, settings.port());
    }

    @Test
    void urlDeliveryRejectsMalformedOrNonHttpAddress() {
        assertThrows(IllegalArgumentException.class,
                () -> PackSettings.from(section("delivery", "url", "public-url", "example.com/pack.zip")));
        assertThrows(IllegalArgumentException.class,
                () -> PackSettings.from(section("delivery", "url", "public-url", "ftp://example.com/pack.zip")));
        assertThrows(IllegalArgumentException.class,
                () -> PackSettings.from(section("delivery", "url", "public-url", "https:///pack.zip")));
    }

    @Test
    void urlDeliveryAcceptsHttpAndHttps() {
        assertEquals("https://cdn.example.com/nexus.zip",
                PackSettings.from(section("delivery", "url",
                        "public-url", "  https://cdn.example.com/nexus.zip  ")).publicUrl());
        assertEquals("http://example.com:8080/nexus.zip",
                PackSettings.from(section("delivery", "url",
                        "public-url", "http://example.com:8080/nexus.zip")).publicUrl());
    }
}

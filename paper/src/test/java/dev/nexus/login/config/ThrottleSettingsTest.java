package dev.nexus.login.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;

class ThrottleSettingsTest {

    @Test
    void defaultsWhenSectionIsMissing() {
        ThrottleSettings settings = ThrottleSettings.from(auth());

        assertEquals(15, settings.window().toMinutes());
        assertEquals(5, settings.accountThreshold());
        assertEquals(0, settings.ipThreshold());
        assertFalse(settings.ipEnabled());
        assertEquals(5, settings.baseLockout().toMinutes(), "eski lockout-minutes devralınmalı");
        assertEquals(1440, settings.maxLockout().toMinutes());
    }

    @Test
    void readsNewSection() {
        ThrottleSettings settings = ThrottleSettings.from(authWithThrottle(
                "window-minutes", 30L,
                "account-threshold", 3,
                "ip-threshold", 20,
                "base-lockout-minutes", 2L,
                "max-lockout-minutes", 120L));

        assertEquals(Duration.ofMinutes(30), settings.window());
        assertEquals(3, settings.accountThreshold());
        assertEquals(20, settings.ipThreshold());
        assertTrue(settings.ipEnabled());
        assertEquals(Duration.ofMinutes(2), settings.baseLockout());
        assertEquals(Duration.ofMinutes(120), settings.maxLockout());
    }

    @Test
    void rejectsInvalidValues() {
        assertThrows(IllegalArgumentException.class, () -> new ThrottleSettings(
                Duration.ofSeconds(30), 5, 0, Duration.ofMinutes(1), Duration.ofMinutes(10)));
        assertThrows(IllegalArgumentException.class, () -> new ThrottleSettings(
                Duration.ofMinutes(15), 0, 0, Duration.ofMinutes(1), Duration.ofMinutes(10)));
        assertThrows(IllegalArgumentException.class, () -> new ThrottleSettings(
                Duration.ofMinutes(15), 5, -1, Duration.ofMinutes(1), Duration.ofMinutes(10)));
        assertThrows(IllegalArgumentException.class, () -> new ThrottleSettings(
                Duration.ofMinutes(15), 5, 0, Duration.ofMinutes(10), Duration.ofMinutes(1)));
        assertThrows(IllegalArgumentException.class, () -> new ThrottleSettings(
                Duration.ofMinutes(15), 5, 0, Duration.ofSeconds(1), Duration.ofMinutes(10)));
    }

    @Test
    void newPinDefaultsToEightDigits() {
        AuthSettings settings = AuthSettings.from(auth());

        assertEquals(8, settings.pinMinLength());
        assertEquals(9, settings.pinMaxLength());
        assertEquals(5, settings.throttle().accountThreshold());
    }

    private static ConfigurationSection auth() {
        MemoryConfiguration config = new MemoryConfiguration();
        config.createSection("auth");
        return config.getConfigurationSection("auth");
    }

    private static ConfigurationSection authWithThrottle(Object... keyValues) {
        MemoryConfiguration config = new MemoryConfiguration();
        ConfigurationSection auth = config.createSection("auth");
        ConfigurationSection throttle = auth.createSection("throttle");
        for (int i = 0; i < keyValues.length; i += 2) {
            throttle.set((String) keyValues[i], keyValues[i + 1]);
        }
        return auth;
    }
}

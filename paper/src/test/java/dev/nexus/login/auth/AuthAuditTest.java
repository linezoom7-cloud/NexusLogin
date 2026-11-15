package dev.nexus.login.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import dev.nexus.login.config.AuditSettings;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class AuthAuditTest {

    private static final UUID UUID_ = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final Logger LOGGER = Logger.getLogger("AuthAuditTest");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path directory;

    @Test
    void writesStructuredAuditLines() throws IOException {
        try (AuthAudit audit = new AuthAudit(directory, LOGGER, CLOCK)) {
            audit.loginFailure("Steve", UUID_, "203.0.113.7", 3, 5);
            audit.lockout("Steve", UUID_, "203.0.113.7", 4, 2);
            audit.loginSuccess("Steve", UUID_, "203.0.113.7", "PIN");
            audit.admin("Admin", "unregister", "Steve");
            audit.flush();
        }

        String content = Files.readString(directory.resolve("nexus-auth.log"));
        assertAll(
                () -> assertTrue(content.contains("2026-01-01T00:00:00Z")),
                () -> assertTrue(content.contains("LOGIN_FAIL")),
                () -> assertTrue(content.contains("attempts=3/5")),
                () -> assertTrue(content.contains("LOCKOUT")),
                () -> assertTrue(content.contains("minutes=4")),
                () -> assertTrue(content.contains("LOGIN_OK")),
                () -> assertTrue(content.contains("kind=PIN")),
                () -> assertTrue(content.contains("ADMIN")),
                () -> assertTrue(content.contains("action=unregister")));
    }

    @Test
    void floodBeyondQueueCapacityLosesNoLines() throws Exception {
        int total = 20_000; // kuyruk kapasitesi (5000) çok aşılır: çağıran yazar, hiçbir satır düşmemeli
        try (AuthAudit audit = new AuthAudit(directory, LOGGER, CLOCK)) {
            for (int i = 0; i < total; i++) {
                audit.preLoginDenied("p" + i, UUID_, "1.2.3.4", "locked");
            }
        } // close() kuyruğu boşaltır

        try (var lines = Files.lines(directory.resolve("nexus-auth.log"))) {
            assertEquals(total, lines.count());
        }
    }

    @Test
    void writeAfterCloseStillReachesTheFileOrConsoleWithoutThrowing() {
        AuthAudit audit = new AuthAudit(directory, LOGGER, CLOCK);
        audit.close();
        assertDoesNotThrow(() -> audit.loginSuccess("Steve", UUID_, "1.2.3.4", "PIN"));
    }

    @Test
    void preventsLogInjection() throws IOException {
        try (AuthAudit audit = new AuthAudit(directory, LOGGER, CLOCK)) {
            audit.loginFailure("bad\nname", UUID_, "1.2.3.4", 1, 5);
        }

        String content = Files.readString(directory.resolve("nexus-auth.log"));
        assertFalse(content.contains("bad\nname"), "satır sonu enjekte edilememeli");
        assertTrue(content.contains("name=bad_name"));
    }

    @Test
    void rotatesWhenFileExceedsLimitAndKeepsConfiguredBackups() throws IOException {
        // 1 KiB sınır, 2 yedek: yüzlerce satır birkaç dosyaya yayılmalı.
        AuditSettings settings = new AuditSettings(1, 2);
        try (AuthAudit audit = new AuthAudit(directory, LOGGER, CLOCK, settings)) {
            for (int i = 0; i < 200; i++) {
                audit.loginSuccess("Steve", UUID_, "203.0.113.7", "PIN");
            }
            audit.flush();
        }

        Path current = directory.resolve("nexus-auth.log");
        assertTrue(Files.exists(current), "etkin günlük dosyası yok");
        assertTrue(Files.size(current) <= settings.maxSizeBytes(),
                "etkin dosya sınırı aşmış: " + Files.size(current));
        assertTrue(Files.exists(directory.resolve("nexus-auth.log.1")), "ilk yedek oluşmadı");
        assertFalse(Files.exists(directory.resolve("nexus-auth.log.3")),
                "keep-files sınırından fazla yedek oluşmuş");
    }
}

package dev.nexus.login.auth;

import dev.nexus.login.config.AuditSettings;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Giriş güvenliği denetim günlüğü: {@code plugins/NexusLogin/nexus-auth.log}.
 *
 * <p>Satırlar tek tek, hemen diske yazılır (flush). Hacim düşüktür (yalnızca giriş
 * olayları ve yönetici işlemleri), bu yüzden ana thread'de kısa bir dosya yazımı
 * kabul edilebilir. Günlük açılamazsa olaylar yalnızca sunucu konsoluna düşer;
 * giriş akışı asla bu yüzden durmaz.
 *
 * <p>Dosya {@link AuditSettings#maxSizeBytes()} sınırına ulaştığında döndürülür:
 * {@code nexus-auth.log.1}, {@code .2} … en fazla {@code keepFiles} dosya saklanır.
 * Böylece bir kaba kuvvet saldırısı diski dolduramaz.
 *
 * <p>Eşik aşımı (kilit) ayrıca konsola {@code WARNING} olarak basılır; böylece saldırı
 * oyuncu şikâyeti beklenmeden fark edilir.
 */
public final class AuthAudit implements AutoCloseable {

    private final Path file;
    private final Logger logger;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();

    private final long maxBytes;
    private final int keepFiles;
    private long currentSize;

    private BufferedWriter writer;

    public AuthAudit(Path dataFolder, Logger logger) {
        this(dataFolder, logger, Clock.systemUTC(),
                new AuditSettings(AuditSettings.DEFAULT_MAX_SIZE_KB, AuditSettings.DEFAULT_KEEP_FILES));
    }

    public AuthAudit(Path dataFolder, Logger logger, AuditSettings settings) {
        this(dataFolder, logger, Clock.systemUTC(), settings);
    }

    /** Testlerde deterministik zaman için; döndürme kapalı. */
    AuthAudit(Path dataFolder, Logger logger, Clock clock) {
        this(dataFolder, logger, clock, new AuditSettings(0, 1));
    }

    AuthAudit(Path dataFolder, Logger logger, Clock clock, AuditSettings settings) {
        this.file = dataFolder.resolve("nexus-auth.log");
        this.logger = logger;
        this.clock = clock;
        this.maxBytes = settings.maxSizeBytes();
        this.keepFiles = settings.keepFiles();
        try {
            Files.createDirectories(dataFolder);
            openWriter();
        } catch (IOException e) {
            logger.log(Level.WARNING, "Denetim günlüğü açılamadı: " + file, e);
            this.writer = null;
        }
    }

    public void loginFailure(String name, UUID uuid, String ip, int attempts, int threshold) {
        write("LOGIN_FAIL",
                "name=" + safe(name),
                "uuid=" + safe(uuid),
                "ip=" + safe(ip),
                "attempts=" + attempts + "/" + threshold);
    }

    public void loginSuccess(String name, UUID uuid, String ip, String kind) {
        write("LOGIN_OK",
                "name=" + safe(name),
                "uuid=" + safe(uuid),
                "ip=" + safe(ip),
                "kind=" + safe(kind));
    }

    public void pinChanged(String name, UUID uuid, String ip) {
        write("PIN_CHANGE",
                "name=" + safe(name),
                "uuid=" + safe(uuid),
                "ip=" + safe(ip));
    }

    public void preLoginDenied(String name, UUID uuid, String ip, String reason) {
        write("PRELOGIN_DENY",
                "name=" + safe(name),
                "uuid=" + safe(uuid),
                "ip=" + safe(ip),
                "reason=" + safe(reason));
    }

    public void lockout(String name, UUID uuid, String ip, long minutes, int level) {
        write("LOCKOUT",
                "name=" + safe(name),
                "uuid=" + safe(uuid),
                "ip=" + safe(ip),
                "minutes=" + minutes,
                "level=" + level);
        logger.warning(() -> "Kaba kuvvet koruması: " + safe(name) + " (" + safe(ip) + ") "
                + minutes + " dk kilitlendi (seviye " + level + "). Ayrıntılar: " + file.getFileName());
    }

    public void admin(String actor, String action, String target) {
        write("ADMIN",
                "actor=" + safe(actor),
                "action=" + safe(action),
                "target=" + safe(target));
    }

    /** Testlerin dosyayı okurken yarım satır görmemesi için hemen diske indirir. */
    public void flush() {
        lock.lock();
        try {
            if (writer != null) {
                writer.flush();
            }
        } catch (IOException e) {
            logger.log(Level.WARNING, "Denetim günlüğü sıfırlanamadı", e);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void close() {
        lock.lock();
        try {
            if (writer != null) {
                writer.close();
                writer = null;
            }
        } catch (IOException e) {
            logger.log(Level.WARNING, "Denetim günlüğü kapatılamadı: " + file, e);
        } finally {
            lock.unlock();
        }
    }

    private void write(String event, String... fields) {
        String timestamp = Instant.now(clock).truncatedTo(ChronoUnit.SECONDS).toString();
        String line = timestamp + "  " + event + "  " + String.join("  ", fields) + System.lineSeparator();
        int incoming = line.getBytes(StandardCharsets.UTF_8).length;

        lock.lock();
        try {
            if (writer == null) {
                logger.info(line.strip());
                return;
            }
            rotateIfNeeded(incoming);
            if (writer == null) {
                logger.info(line.strip());
                return;
            }
            writer.write(line);
            writer.flush();
            currentSize += incoming;
        } catch (IOException e) {
            logger.log(Level.WARNING, "Denetim günlüğüne yazılamadı: " + file, e);
        } finally {
            lock.unlock();
        }
    }

    /** Kilit zaten tutulurken çağrılır. */
    private void rotateIfNeeded(int incoming) throws IOException {
        if (maxBytes <= 0 || currentSize + incoming <= maxBytes) {
            return;
        }
        writer.close();
        writer = null;
        try {
            // En eski dosya en yenisiyle ezilir; böylece .keepFiles+1 gibi artık dosya kalmaz.
            for (int i = keepFiles; i >= 1; i--) {
                Path source = i == 1 ? file : rotated(i - 1);
                if (Files.exists(source)) {
                    Files.move(source, rotated(i), StandardCopyOption.REPLACE_EXISTING);
                }
            }
        } finally {
            // Döndürme kısmen başarısız olsa bile yazmaya devam edebilmek için yeniden aç.
            openWriter();
        }
    }

    private void openWriter() throws IOException {
        this.writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        this.currentSize = Files.exists(file) ? Files.size(file) : 0;
    }

    private Path rotated(int index) {
        return file.resolveSibling(file.getFileName() + "." + index);
    }

    /** Log satırına satır sonu/sekme enjeksiyonunu engeller. */
    private static String safe(Object value) {
        if (value == null) {
            return "-";
        }
        String cleaned = value.toString().replaceAll("[\\r\\n\\t\\p{Cntrl}]", "_").trim();
        return cleaned.isEmpty() ? "-" : cleaned;
    }
}

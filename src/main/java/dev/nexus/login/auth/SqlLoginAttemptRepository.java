package dev.nexus.login.auth;

import dev.nexus.login.database.DataAccessException;
import dev.nexus.login.database.DatabaseManager;
import dev.nexus.login.database.DatabaseType;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Kaba kuvvet sayaçlarının SQLite/MariaDB kalıcılığı.
 *
 * <p>Okuma eklenti açılışında bir kez yapılır. Yazımlar tek bir sıralı iş parçacığında
 * çalışır: aynı anahtar için iki güncelleme yarışırsa eski durum yenisini ezemez.
 * Böylece ana thread hiçbir zaman JDBC beklemez.
 */
public final class SqlLoginAttemptRepository implements LoginAttemptRepository, AutoCloseable {

    private static final String COLUMNS =
            "attempt_key, attempts, window_start, locked_until, block_count, updated_at";
    private static final String SELECT_ALL = "SELECT " + COLUMNS + " FROM nexus_login_attempts";

    private static final String SQLITE_UPSERT =
            "INSERT INTO nexus_login_attempts (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?) "
                    + "ON CONFLICT(attempt_key) DO UPDATE SET "
                    + "attempts = excluded.attempts, "
                    + "window_start = excluded.window_start, "
                    + "locked_until = excluded.locked_until, "
                    + "block_count = excluded.block_count, "
                    + "updated_at = excluded.updated_at";

    private static final String MARIADB_UPSERT =
            "INSERT INTO nexus_login_attempts (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?) "
                    + "ON DUPLICATE KEY UPDATE "
                    + "attempts = VALUES(attempts), "
                    + "window_start = VALUES(window_start), "
                    + "locked_until = VALUES(locked_until), "
                    + "block_count = VALUES(block_count), "
                    + "updated_at = VALUES(updated_at)";

    private static final String DELETE = "DELETE FROM nexus_login_attempts WHERE attempt_key = ?";
    private static final String PURGE =
            "DELETE FROM nexus_login_attempts WHERE updated_at < ? AND locked_until <= ?";

    private static final long CLOSE_TIMEOUT_SECONDS = 5;
    /**
     * Yazma kuyruğu SINIRLIDIR. Sınırsız kuyruk + yavaş DB, kaba kuvvet saldırısında
     * milyonlarca görev biriktirip belleği tüketebilirdi. Kuyruk dolunca yazma atlanır;
     * asıl doğruluk kaynağı bellekteki {@link LoginThrottle} durumudur.
     */
    private static final int WRITE_QUEUE_CAPACITY = 1_024;
    private static final long DROP_LOG_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(30);

    private final DatabaseManager database;
    private final Executor ioExecutor;
    private final Logger logger;
    private final String upsert;
    private final ExecutorService writes;
    private final AtomicLong lastDropLogNanos = new AtomicLong();

    public SqlLoginAttemptRepository(DatabaseManager database, Executor ioExecutor, Logger logger) {
        this.database = database;
        this.ioExecutor = ioExecutor;
        this.logger = logger;
        this.upsert = database.type() == DatabaseType.SQLITE ? SQLITE_UPSERT : MARIADB_UPSERT;
        this.writes = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(WRITE_QUEUE_CAPACITY),
                runnable -> {
                    Thread thread = new Thread(runnable, "nexus-attempts");
                    thread.setDaemon(true);
                    return thread;
                },
                // Kuyruk dolu: görevi düşür ama bunu (seyrek de olsa) logla.
                (task, executor) -> logDroppedWrite());
    }

    /** Kuyruk taşmasını en fazla 30 saniyede bir loglar; saldırı altında log spam'i olmaz. */
    private void logDroppedWrite() {
        long now = System.nanoTime();
        long previous = lastDropLogNanos.get();
        if (now - previous >= DROP_LOG_INTERVAL_NANOS && lastDropLogNanos.compareAndSet(previous, now)) {
            logger.warning("Kaba kuvvet yazma kuyruğu dolu; bazı güncellemeler kalıcılaştırılamadı "
                    + "(bellek korunuyor).");
        }
    }

    @Override
    public CompletableFuture<List<LoginAttempt>> loadAll() {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = database.getConnection();
                 PreparedStatement ps = connection.prepareStatement(SELECT_ALL);
                 ResultSet rs = ps.executeQuery()) {
                List<LoginAttempt> attempts = new ArrayList<>();
                while (rs.next()) {
                    attempts.add(new LoginAttempt(
                            rs.getString("attempt_key"),
                            rs.getInt("attempts"),
                            rs.getLong("window_start"),
                            rs.getLong("locked_until"),
                            rs.getInt("block_count"),
                            rs.getLong("updated_at")));
                }
                return attempts;
            } catch (SQLException e) {
                throw new DataAccessException("Kaba kuvvet sayaçları yüklenemedi: " + e.getMessage(), e);
            }
        }, ioExecutor);
    }

    @Override
    public void save(LoginAttempt attempt) {
        writes.execute(() -> {
            try (Connection connection = database.getConnection();
                 PreparedStatement ps = connection.prepareStatement(upsert)) {
                ps.setString(1, attempt.key());
                ps.setInt(2, attempt.attempts());
                ps.setLong(3, attempt.windowStart());
                ps.setLong(4, attempt.lockedUntil());
                ps.setInt(5, attempt.blockCount());
                ps.setLong(6, attempt.updatedAt());
                ps.executeUpdate();
            } catch (SQLException e) {
                logger.log(Level.WARNING, "Kaba kuvvet sayacı yazılamadı: " + attempt.key(), e);
            }
        });
    }

    @Override
    public void delete(String key) {
        // Admin işlemleri (kilit açma / hesap silme) yazma kuyruğu taşmasından etkilenmemelidir;
        // bu nedenle doğrudan G/Ç executor'ünde çalıştırılır.
        CompletableFuture.runAsync(() -> {
            try (Connection connection = database.getConnection();
                 PreparedStatement ps = connection.prepareStatement(DELETE)) {
                ps.setString(1, key);
                ps.executeUpdate();
            } catch (SQLException e) {
                logger.log(Level.WARNING, "Kaba kuvvet sayacı silinemedi: " + key, e);
            }
        }, ioExecutor);
    }

    @Override
    public void purgeOlderThan(long epochMillis) {
        writes.execute(() -> {
            try (Connection connection = database.getConnection();
                 PreparedStatement ps = connection.prepareStatement(PURGE)) {
                ps.setLong(1, epochMillis);
                ps.setLong(2, System.currentTimeMillis());
                int removed = ps.executeUpdate();
                if (removed > 0) {
                    logger.fine(() -> "Kaba kuvvet sayaçlarından " + removed + " eski kayıt temizlendi.");
                }
            } catch (SQLException e) {
                logger.log(Level.WARNING, "Eski kaba kuvvet sayaçları temizlenemedi", e);
            }
        });
    }

    /** Bekleyen yazımların bitmesini bekler; veritabanı havuzundan ÖNCE çağrılmalıdır. */
    @Override
    public void close() {
        writes.shutdown();
        try {
            if (!writes.awaitTermination(CLOSE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                writes.shutdownNow();
            }
        } catch (InterruptedException e) {
            writes.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}

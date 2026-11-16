package dev.nexus.login.totp;

import dev.nexus.login.database.DataAccessException;
import dev.nexus.login.database.DatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** {@code nexus_totp} erişimi. Satır varsa hesapta 2FA etkindir. */
public final class TotpRepository {

    /** @param secretEnc {@link TotpVault} ile şifreli secret; düz metin asla saklanmaz */
    public record Entry(UUID uuid, String secretEnc, long lastStep) {
    }

    private final DatabaseManager database;
    private final Executor executor;

    public TotpRepository(DatabaseManager database, Executor ioExecutor) {
        this.database = database;
        this.executor = ioExecutor;
    }

    public CompletableFuture<Optional<Entry>> find(UUID uuid) {
        return with(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT secret_enc, last_step FROM nexus_totp WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? Optional.of(new Entry(uuid, rs.getString(1), rs.getLong(2))) : Optional.empty();
                }
            }
        });
    }

    /**
     * Kaydı ekler. Onay kodunun adımı {@code lastStep} olarak yazılır: kayıtta kullanılan kod girişte
     * tekrar kullanılamaz. Satır zaten varsa false (başka sunucu aynı anda kaydetti).
     */
    public CompletableFuture<Boolean> insert(UUID uuid, String secretEnc, long lastStep, long nowMs) {
        return with(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO nexus_totp (uuid, secret_enc, last_step, created_at) VALUES (?, ?, ?, ?)")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, secretEnc);
                ps.setLong(3, lastStep);
                ps.setLong(4, nowMs);
                return ps.executeUpdate() == 1;
            } catch (SQLException e) {
                if (exists(c, uuid)) {
                    return false;
                }
                throw e;
            }
        });
    }

    /**
     * Tekrar kullanım koruması: yalnızca son kabul edilen adımdan BÜYÜK bir adım kabul edilir. Tek satır
     * üzerinde atomik olduğu için aynı DB'yi paylaşan iki sunucu aynı kodu iki kez kabul edemez.
     */
    public CompletableFuture<Boolean> acceptStep(UUID uuid, long step) {
        return with(c -> {
            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE nexus_totp SET last_step = ? WHERE uuid = ? AND last_step < ?")) {
                ps.setLong(1, step);
                ps.setString(2, uuid.toString());
                ps.setLong(3, step);
                return ps.executeUpdate() == 1;
            }
        });
    }

    public CompletableFuture<Boolean> delete(UUID uuid) {
        return with(c -> {
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM nexus_totp WHERE uuid = ?")) {
                ps.setString(1, uuid.toString());
                return ps.executeUpdate() > 0;
            }
        });
    }

    public CompletableFuture<Boolean> isEmpty() {
        return with(c -> {
            try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM nexus_totp LIMIT 1");
                 ResultSet rs = ps.executeQuery()) {
                return !rs.next();
            }
        });
    }

    private static boolean exists(Connection c, UUID uuid) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM nexus_totp WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    @FunctionalInterface
    private interface SqlFunction<T> {
        T apply(Connection connection) throws SQLException;
    }

    private <T> CompletableFuture<T> with(SqlFunction<T> work) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = database.getConnection()) {
                return work.apply(connection);
            } catch (SQLException e) {
                throw new DataAccessException(e.getMessage(), e);
            }
        }, executor);
    }
}

package dev.nexus.login.account;

import dev.nexus.login.database.DataAccessException;
import dev.nexus.login.database.DatabaseManager;
import org.jetbrains.annotations.Nullable;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * SQLite ve MariaDB için ortak JDBC implementasyonu. Tüm sorgular PreparedStatement ile
 * parametrelenir (SQL injection yok); her çağrı havuzdan bir bağlantı ödünç alıp hemen iade eder.
 */
public final class SqlAccountRepository implements AccountRepository {

    private static final String COLUMNS = "id, uuid, player_name, password_hash, is_premium, reg_ip, last_ip";

    private static final String SELECT_BY_UUID = "SELECT " + COLUMNS + " FROM nexus_accounts WHERE uuid = ?";
    private static final String SELECT_BY_NAME = "SELECT " + COLUMNS + " FROM nexus_accounts WHERE player_name = ?";
    private static final String INSERT = """
            INSERT INTO nexus_accounts (uuid, player_name, password_hash, is_premium, reg_ip, last_ip)
            VALUES (?, ?, ?, ?, ?, ?)
            """;
    private static final String UPDATE_PASSWORD = "UPDATE nexus_accounts SET password_hash = ? WHERE uuid = ?";
    private static final String UPDATE_LAST_LOGIN = "UPDATE nexus_accounts SET player_name = ?, last_ip = ? WHERE uuid = ?";
    private static final String UPDATE_PREMIUM = "UPDATE nexus_accounts SET is_premium = ? WHERE uuid = ?";
    private static final String DELETE = "DELETE FROM nexus_accounts WHERE uuid = ?";
    private static final String COUNT_BY_REG_IP = "SELECT COUNT(*) FROM nexus_accounts WHERE reg_ip = ?";
    private static final String SELECT_NAME_HOLDERS =
            "SELECT uuid FROM nexus_accounts WHERE player_name = ? AND uuid <> ?";
    private static final String RENAME = "UPDATE nexus_accounts SET player_name = ? WHERE uuid = ?";

    private final DatabaseManager database;
    private final Executor executor;

    public SqlAccountRepository(DatabaseManager database, Executor ioExecutor) {
        this.database = database;
        this.executor = ioExecutor;
    }

    @Override
    public CompletableFuture<Optional<Account>> findByUuid(UUID uuid) {
        return withConnection(c -> querySingle(c, SELECT_BY_UUID, uuid.toString()));
    }

    @Override
    public CompletableFuture<Optional<Account>> findByName(String playerName) {
        return withConnection(c -> querySingle(c, SELECT_BY_NAME, playerName));
    }

    @Override
    public CompletableFuture<Account> create(UUID uuid, String playerName, @Nullable String passwordHash,
                                             boolean premium, @Nullable String ip) {
        return withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(INSERT, Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, uuid.toString());
                ps.setString(2, playerName);
                ps.setString(3, passwordHash);
                ps.setBoolean(4, premium);
                ps.setString(5, ip);
                ps.setString(6, ip);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (!keys.next()) {
                        throw new SQLException("INSERT sonrası üretilen id alınamadı");
                    }
                    return new Account(keys.getLong(1), uuid, playerName, passwordHash, premium, ip, ip);
                }
            }
        });
    }

    @Override
    public CompletableFuture<Boolean> updatePasswordHash(UUID uuid, String passwordHash) {
        return withConnection(c -> update(c, UPDATE_PASSWORD, passwordHash, uuid.toString()));
    }

    @Override
    public CompletableFuture<Boolean> updateLastLogin(UUID uuid, String playerName, String ip) {
        return withConnection(c -> update(c, UPDATE_LAST_LOGIN, playerName, ip, uuid.toString()));
    }

    @Override
    public CompletableFuture<Boolean> setPremium(UUID uuid, boolean premium) {
        return withConnection(c -> update(c, UPDATE_PREMIUM, premium, uuid.toString()));
    }

    @Override
    public CompletableFuture<Boolean> delete(UUID uuid) {
        return withConnection(c -> update(c, DELETE, uuid.toString()));
    }

    @Override
    public CompletableFuture<Integer> releaseName(String playerName, UUID exceptUuid) {
        return withConnection(c -> {
            java.util.List<String> holders = new java.util.ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement(SELECT_NAME_HOLDERS)) {
                ps.setString(1, playerName);
                ps.setString(2, exceptUuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        holders.add(rs.getString(1));
                    }
                }
            }
            int released = 0;
            for (String holder : holders) {
                // Yer tutucu: '#' + UUID'nin ilk 15 hex hanesi = 16 karakter (sütun sınırı).
                // '#' Minecraft isimlerinde geçersiz olduğu için gerçek bir isimle çakışamaz.
                String placeholder = "#" + holder.replace("-", "").substring(0, 15);
                released += update(c, RENAME, placeholder, holder) ? 1 : 0;
            }
            return released;
        });
    }

    @Override
    public CompletableFuture<Integer> countByRegistrationIp(String ip) {
        return withConnection(c -> {
            try (PreparedStatement ps = c.prepareStatement(COUNT_BY_REG_IP)) {
                ps.setString(1, ip);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? rs.getInt(1) : 0;
                }
            }
        });
    }

    // ───────────────────────── yardımcılar ─────────────────────────

    @FunctionalInterface
    private interface SqlFunction<T> {
        T apply(Connection connection) throws SQLException;
    }

    /** Bağlantı ödünç al → işi yap → iade et; hepsi G/Ç (virtual thread) executor'ünde. */
    private <T> CompletableFuture<T> withConnection(SqlFunction<T> work) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = database.getConnection()) {
                return work.apply(connection);
            } catch (SQLException e) {
                throw new DataAccessException(e.getMessage(), e);
            }
        }, executor);
    }

    private static Optional<Account> querySingle(Connection c, String sql, String param) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(map(rs)) : Optional.empty();
            }
        }
    }

    private static boolean update(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                // Java 21 pattern matching for switch: parametre tipine göre doğru setter.
                switch (params[i]) {
                    case String s -> ps.setString(i + 1, s);
                    case Boolean b -> ps.setBoolean(i + 1, b);
                    case Long l -> ps.setLong(i + 1, l);
                    case Integer n -> ps.setInt(i + 1, n);
                    case null -> ps.setObject(i + 1, null);
                    default -> throw new SQLException("Desteklenmeyen parametre tipi: " + params[i].getClass());
                }
            }
            return ps.executeUpdate() > 0;
        }
    }

    private static Account map(ResultSet rs) throws SQLException {
        return new Account(
                rs.getLong("id"),
                UUID.fromString(rs.getString("uuid")),
                rs.getString("player_name"),
                rs.getString("password_hash"),
                rs.getBoolean("is_premium"),
                rs.getString("reg_ip"),
                rs.getString("last_ip")
        );
    }
}

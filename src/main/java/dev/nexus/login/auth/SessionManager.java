package dev.nexus.login.auth;

import dev.nexus.login.account.Account;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Aktif oturumlar, ön-yüklenmiş hesaplar ve "beni hatırla" kayıtları.
 * Haritalar concurrent'tır çünkü asenkron eventler (pre-login, chat) de bunları okur.
 */
public final class SessionManager {

    /** AsyncPlayerPreLoginEvent'te yüklenip PlayerJoinEvent'te tüketilen hesap. */
    public record Preloaded(@Nullable Account account, Instant loadedAt) {
    }

    private record Remembered(String ip, Instant expiresAt) {
    }

    private static final Duration PRELOAD_TTL = Duration.ofMinutes(1);

    private final Map<UUID, AuthSession> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, Preloaded> preloaded = new ConcurrentHashMap<>();
    private final Map<UUID, Remembered> remembered = new ConcurrentHashMap<>();

    AuthSession create(UUID uuid, String ip, Duration timeout) {
        AuthSession session = new AuthSession(uuid, ip, timeout);
        sessions.put(uuid, session);
        return session;
    }

    public @Nullable AuthSession get(UUID uuid) {
        return sessions.get(uuid);
    }

    @Nullable AuthSession remove(UUID uuid) {
        return sessions.remove(uuid);
    }

    Collection<AuthSession> all() {
        return sessions.values();
    }

    /**
     * Oturumu olmayan oyuncular (ör. NPC eklentilerinin sahte Player nesneleri) kısıtlanmaz.
     */
    public boolean isRestricted(Player player) {
        AuthSession session = sessions.get(player.getUniqueId());
        return session != null && session.isRestricted();
    }

    // ── ön-yükleme ──

    public void preload(UUID uuid, @Nullable Account account) {
        preloaded.put(uuid, new Preloaded(account, Instant.now()));
    }

    Optional<Preloaded> takePreloaded(UUID uuid) {
        return Optional.ofNullable(preloaded.remove(uuid));
    }

    /** Pre-login'den geçip başka bir eklenti tarafından reddedilen bağlantıların artıklarını temizler. */
    void purgeStalePreloads() {
        Instant limit = Instant.now().minus(PRELOAD_TTL);
        preloaded.values().removeIf(p -> p.loadedAt().isBefore(limit));
        Instant now = Instant.now();
        remembered.values().removeIf(r -> now.isAfter(r.expiresAt()));
    }

    // ── beni hatırla ──

    void remember(UUID uuid, String ip, Duration duration) {
        if (!duration.isZero()) {
            remembered.put(uuid, new Remembered(ip, Instant.now().plus(duration)));
        }
    }

    /** Tek kullanımlık: eşleşse de eşleşmese de kayıt silinir. */
    boolean consumeRemembered(UUID uuid, String ip) {
        Remembered r = remembered.remove(uuid);
        return r != null && r.ip().equals(ip) && Instant.now().isBefore(r.expiresAt());
    }

    public void forget(UUID uuid) {
        remembered.remove(uuid);
    }
}

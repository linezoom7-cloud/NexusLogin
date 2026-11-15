package dev.nexus.login.auth;

import dev.nexus.login.account.Account;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
    /**
     * Hesabı silinen oyuncunun çıkışında {@link #remember} çağrılsa bile kayıt oluşmasın.
     * Kick ile quit event'i arasındaki sıra garanti olmadığı için bu bayrak gerekir.
     */
    private final Set<UUID> rememberBlocked = ConcurrentHashMap.newKeySet();

    /** Çıkış yapmış oyuncular; yeniden girişte ve periyodik temizlikte silinir. */
    private final Set<UUID> leaving = ConcurrentHashMap.newKeySet();

    AuthSession create(UUID uuid, String ip, Duration timeout) {
        leaving.remove(uuid);
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
     * Fail-closed: oturumu olmayan gerçek oyuncu kısıtlıdır (ör. /reload sonrası veritabanı
     * hazır olana kadar). Yalnızca NPC eklentilerinin sahte Player nesneleri muaftır.
     */
    public boolean isRestricted(Player player) {
        AuthSession session = sessions.get(player.getUniqueId());
        if (session != null) {
            return session.isRestricted();
        }
        // Citizens ve benzerleri NPC'lerini "NPC" metadata'sıyla işaretler. Çıkış yapan oyuncuya
        // dokunan başka eklentiler (savaştan kaçanı cezalandıran, konum kaydeden) engellenmesin.
        return !leaving.contains(player.getUniqueId()) && !player.hasMetadata("NPC");
    }

    /** Oyuncu çıkıyor: oturumu silinmiş olsa da kısa süre kısıtlı sayılmaz. */
    void leaving(UUID uuid) {
        leaving.add(uuid);
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
        leaving.clear();
        Instant now = Instant.now();
        remembered.values().removeIf(r -> now.isAfter(r.expiresAt()));
        if (rememberBlocked.size() > 5_000) {
            rememberBlocked.clear();
        }
    }

    // ── beni hatırla ──

    void remember(UUID uuid, String ip, Duration duration) {
        if (!duration.isZero() && !rememberBlocked.contains(uuid)) {
            remembered.put(uuid, new Remembered(ip, Instant.now().plus(duration)));
        }
    }

    /** Başarılı bir giriş/kayıt sonrası "beni hatırla" yeniden etkinleşir. */
    void allowRemember(UUID uuid) {
        rememberBlocked.remove(uuid);
    }

    /** Tek kullanımlık: eşleşse de eşleşmese de kayıt silinir. */
    boolean consumeRemembered(UUID uuid, String ip) {
        Remembered r = remembered.remove(uuid);
        return r != null && r.ip().equals(ip) && Instant.now().isBefore(r.expiresAt());
    }

    /**
     * PIN değişince eski "beni hatırla" kaydı geçersiz olmalı: aksi halde PIN'i değiştiren
     * oyuncu, aynı IP'den gelen eski oturum kaydıyla hâlâ PIN'siz girebilirdi.
     */
    public void invalidateRemembered(UUID uuid) {
        remembered.remove(uuid);
    }

    /**
     * Hesap silindiğinde/unregister edildiğinde çağrılır. Ön-yüklenmiş hesap da temizlenir;
     * aksi halde pre-login ile join arasındaki oyuncu silinmiş hesabın eski hash'iyle bir kez
     * giriş yapabilirdi.
     */
    public void forget(UUID uuid) {
        rememberBlocked.add(uuid);
        remembered.remove(uuid);
        preloaded.remove(uuid);
    }
}

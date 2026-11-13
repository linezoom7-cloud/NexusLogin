package dev.nexus.login.auth;

import dev.nexus.login.config.ThrottleSettings;
import org.jetbrains.annotations.Nullable;

import java.time.Clock;
import java.time.Duration;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Kalıcı, artan cezalı kaba kuvvet koruması.
 *
 * <p>Önceki sürüm yalnızca bellekte bir IP kilidi tutuyordu ve sayaç oturum başınaydı:
 * 4 yanlış PIN girip çıkan saldırgan yeniden bağlanınca sayaç sıfırlanıyordu. Artık:
 * <ul>
 *   <li>sayaçlar <b>hesap</b> (UUID) ve isteğe bağlı <b>IP</b> bazında tutulur,</li>
 *   <li>durum veritabanına yazılır → yeniden bağlanma veya yeniden başlatma sıfırlamaz,</li>
 *   <li>her yeni kilitte ceza ikiye katlanır ({@code maxLockout} tavanına kadar),</li>
 *   <li>başarılı giriş yalnızca <b>hesap</b> anahtarını sıfırlar; IP anahtarı bilinçli
 *       olarak korunur (yoksa saldırgan kendi hesabıyla IP bütçesini yenileyebilirdi).</li>
 * </ul>
 *
 * <p>Kontrol {@code AsyncPlayerPreLoginEvent} içinde yapılır: kilitli hesap sunucuya hiç
 * giremez. Sınıf thread-safe'tir; sayaç güncellemeleri atomiktir.
 */
public final class LoginThrottle {

    /** Aktif bir kilidin özeti. */
    public record Verdict(String key, Duration remaining, int blockCount) {
        /** Kullanıcıya gösterilecek dakika (yukarı yuvarlanır, en az 1). */
        public long minutes() {
            return Math.max(1, (remaining.toSeconds() + 59) / 60);
        }
    }

    /** Bir hatalı denemenin sonucu. */
    public record FailureResult(@Nullable Verdict lock, int attempts, int threshold) {
        public boolean locked() {
            return lock != null;
        }

        public int remaining() {
            return Math.max(0, threshold - attempts);
        }
    }

    private volatile ThrottleSettings settings;
    private final Clock clock;
    private final @Nullable LoginAttemptRepository repository;
    private final Map<String, LoginAttempt> states = new ConcurrentHashMap<>();

    public LoginThrottle(ThrottleSettings settings, Clock clock, @Nullable LoginAttemptRepository repository) {
        this.settings = settings;
        this.clock = clock;
        this.repository = repository;
    }

    /**
     * {@code /nexus reload} sonrası yeni eşikleri uygular. Bellekteki sayaçlar korunur;
     * yalnızca bundan sonraki kontroller yeni ayarları kullanır.
     */
    public void updateSettings(ThrottleSettings newSettings) {
        this.settings = newSettings;
    }

    public static String ipKey(String ip) {
        return "ip:" + ip.toLowerCase(Locale.ROOT);
    }

    public static String accountKey(UUID uuid) {
        return "acct:" + uuid.toString().toLowerCase(Locale.ROOT);
    }

    /** Kalıcı kayıtları belleğe alır. Girişler kabul edilmeden önce çağrılmalıdır. */
    public void load(Collection<LoginAttempt> saved) {
        long now = clock.millis();
        long window = settings.window().toMillis();
        for (LoginAttempt attempt : saved) {
            boolean locked = attempt.lockedUntil() > now;
            boolean recent = now - attempt.windowStart() <= window;
            if (locked || recent || attempt.blockCount() > 0) {
                // Kilidi bitmiş ama ceza seviyesi olan kayıt: geçmişi hatırla, sayacı sıfırla.
                states.put(attempt.key(), locked || recent
                        ? attempt
                        : new LoginAttempt(attempt.key(), 0, now, 0, attempt.blockCount(), now));
            }
        }
    }

    /**
     * IP ve/veya hesap anahtarından hangisi daha uzun süre kilitliyse onu döndürür.
     * Süresi dolmuş kilitler burada temizlenir.
     */
    public Optional<Verdict> check(String ip, @Nullable UUID uuid) {
        long now = clock.millis();
        Verdict best = null;
        if (settings.ipEnabled()) {
            best = later(best, clearExpired(ipKey(ip), now));
        }
        if (uuid != null) {
            best = later(best, clearExpired(accountKey(uuid), now));
        }
        return Optional.ofNullable(best);
    }

    /**
     * Bir hatalı PIN denemesini hem hesap hem (açıksa) IP sayacına yazar.
     * Eşik aşıldıysa kilit bilgisi döner; çağıran oyuncuyu atmalıdır.
     */
    public FailureResult recordFailure(String ip, UUID uuid) {
        long now = clock.millis();
        Verdict best = null;
        if (settings.ipEnabled()) {
            LoginAttempt after = bump(ipKey(ip), settings.ipThreshold(), now);
            best = later(best, verdictOf(after, now));
        }
        LoginAttempt account = bump(accountKey(uuid), settings.accountThreshold(), now);
        best = later(best, verdictOf(account, now));
        return new FailureResult(best, account.attempts(), settings.accountThreshold());
    }

    /**
     * Başarılı girişte YALNIZCA hesap anahtarını sıfırlar.
     * IP anahtarına dokunulmaz: aksi halde saldırgan kendi hesabına girip IP cezasını silerdi.
     */
    public void resetAccount(UUID uuid) {
        String key = accountKey(uuid);
        // Başarılı giriş yolu: yalnızca gerçekten bellekte tutulan bir sayaç varsa yaz.
        if (states.remove(key) != null && repository != null) {
            repository.delete(key);
        }
    }

    /**
     * Yönetici kilidi elle açar: hesap anahtarını ve bilinen IP anahtarlarını hem
     * bellekten hem veritabanından siler. Başarılı girişte kullanılan
     * {@link #resetAccount(UUID)} yalnızca hesabı temizler; bu metot IP cezasını da kaldırır.
     */
    public void reset(UUID uuid, Collection<String> ips) {
        remove(accountKey(uuid));
        for (String ip : ips) {
            resetIp(ip);
        }
    }

    /** Yönetici tek bir IP cezasını elle siler. {@code null} veya boş değerler yok sayılır. */
    public void resetIp(@Nullable String ip) {
        if (ip == null || ip.isBlank()) {
            return;
        }
        remove(ipKey(ip));
    }

    /**
     * Süresi dolmuş, artık hiçbir işe yaramayan sayaçları bellekten temizler.
     *
     * <p>Uzun süre sessiz kalan bir ceza seviyesi ({@code blockCount}) kademeli olarak
     * azaltılır ve sıfıra inince kayıt tamamen unutulur. Böylece yoğun bir saldırıda
     * binlerce farklı IP'den gelen kayıtlar belleği süresiz şişirmez.
     */
    public void purgeStale() {
        long now = clock.millis();
        long window = settings.window().toMillis();
        long history = Math.max(window * 24, settings.maxLockout().toMillis());
        for (String key : states.keySet()) {
            LoginAttempt attempt = states.get(key);
            if (attempt == null || attempt.lockedUntil() > now) {
                continue;
            }
            long idle = now - attempt.updatedAt();
            if (idle <= window) {
                continue;
            }
            if (attempt.blockCount() == 0) {
                if (states.remove(key, attempt) && repository != null) {
                    repository.delete(key);
                }
                continue;
            }
            if (idle > history) {
                int nextBlock = attempt.blockCount() - 1;
                LoginAttempt decayed = new LoginAttempt(key, 0, now, 0, nextBlock, now);
                if (nextBlock <= 0) {
                    states.remove(key, attempt);
                    if (repository != null) {
                        repository.delete(key);
                    }
                } else if (states.replace(key, attempt, decayed)) {
                    save(decayed);
                }
            }
        }
    }

    /** Tanılama/testler için: bellekte tutulan sayaç sayısı. */
    int cachedEntryCount() {
        return states.size();
    }

    private void remove(String key) {
        states.remove(key);
        if (repository != null) {
            // Kayıt bellekte olmasa bile veritabanında kalmış olabilir (ör. yüklemede
            // filtrelenmiş bir satır). Yönetici işlemi her durumda kalıcı olmalı.
            repository.delete(key);
        }
    }

    private @Nullable Verdict clearExpired(String key, long now) {
        LoginAttempt attempt = states.get(key);
        if (attempt == null) {
            return null;
        }
        long left = attempt.lockedUntil() - now;
        if (left > 0) {
            return new Verdict(key, Duration.ofMillis(left), attempt.blockCount());
        }
        if (attempt.lockedUntil() != 0) {
            LoginAttempt cleared = new LoginAttempt(key, attempt.attempts(), attempt.windowStart(), 0,
                    attempt.blockCount(), now);
            states.put(key, cleared);
            save(cleared);
        }
        return null;
    }

    private LoginAttempt bump(String key, int threshold, long now) {
        long window = settings.window().toMillis();
        LoginAttempt updated = states.compute(key, (k, previous) -> {
            LoginAttempt current = previous != null
                    ? previous
                    : new LoginAttempt(k, 0, now, 0, 0, now);
            if (current.lockedUntil() > now) {
                return current;
            }
            int attempts = current.attempts();
            long windowStart = current.windowStart();
            int blockCount = current.blockCount();
            if (now - windowStart > window) {
                // Pencere doldu. Tam bir sessiz pencere geçtiyse ceza seviyesini bir kademe azalt:
                // yıllar önceki bir olay yüzünden sonsuza dek 24 saat kilit kalmasın.
                if (attempts == 0 && blockCount > 0) {
                    blockCount--;
                }
                attempts = 0;
                windowStart = now;
            }
            attempts++;
            if (attempts >= threshold) {
                int nextBlock = blockCount + 1;
                return new LoginAttempt(k, 0, now, now + lockMillis(nextBlock), nextBlock, now);
            }
            return new LoginAttempt(k, attempts, windowStart, 0, blockCount, now);
        });
        save(updated);
        return updated;
    }

    /** İlk kilit {@code base}, her sonraki kilit iki katı; {@code maxLockout} ile sınırlı. */
    private long lockMillis(int blockCount) {
        long base = settings.baseLockout().toMillis();
        long max = settings.maxLockout().toMillis();
        int shift = Math.min(Math.max(0, blockCount - 1), 20);
        long millis = base << shift;
        return millis <= 0 || millis > max ? max : millis;
    }

    private void save(LoginAttempt attempt) {
        if (repository != null) {
            repository.save(attempt);
        }
    }

    private static @Nullable Verdict verdictOf(LoginAttempt attempt, long now) {
        long left = attempt.lockedUntil() - now;
        return left > 0 ? new Verdict(attempt.key(), Duration.ofMillis(left), attempt.blockCount()) : null;
    }

    private static @Nullable Verdict later(@Nullable Verdict first, @Nullable Verdict second) {
        if (first == null) {
            return second;
        }
        if (second == null) {
            return first;
        }
        return second.remaining().compareTo(first.remaining()) > 0 ? second : first;
    }
}

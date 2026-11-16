package dev.nexus.login.auth;

import dev.nexus.login.config.ThrottleSettings;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Kalıcı sayaç davranışı: yeniden bağlanma/restart sıfırlamamalı, ceza artmalı,
 * başarılı giriş yalnızca hesabı temizlemeli.
 */
class LoginThrottleTest {

    private static final String IP = "203.0.113.7";
    private static final UUID ACCOUNT = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private final MutableClock clock = new MutableClock(Instant.parse("2026-01-01T00:00:00Z"));

    @Test
    void accountLockSurvivesReconnectAndRestart() {
        FakeRepository repository = new FakeRepository();
        LoginThrottle throttle = new LoginThrottle(settings(3, 0), clock, repository);

        assertFalse(throttle.recordFailure(IP, ACCOUNT).locked());
        assertFalse(throttle.recordFailure(IP, ACCOUNT).locked());
        LoginThrottle.FailureResult third = throttle.recordFailure(IP, ACCOUNT);

        assertTrue(third.locked(), "3. hata hesabı kilitlemeli");
        assertEquals(1, third.lock().blockCount());

        // "Yeniden bağlanma": aynı süreç, yeni bağlantı farklı IP'den gelse bile kilitli.
        assertTrue(throttle.check("198.51.100.9", ACCOUNT).isPresent());

        // "Restart": yalnızca veritabanındaki kayıtlar var.
        LoginThrottle restarted = new LoginThrottle(settings(3, 0), clock, new FakeRepository());
        restarted.load(repository.saved.values());
        assertTrue(restarted.check(IP, ACCOUNT).isPresent(), "kilit restart'tan sağ çıkmalı");
    }

    @Test
    void lockDurationDoublesOnEachBlock() {
        LoginThrottle throttle = new LoginThrottle(settings(2, 0), clock, null);

        throttle.recordFailure(IP, ACCOUNT);
        LoginThrottle.FailureResult first = throttle.recordFailure(IP, ACCOUNT);
        assertTrue(first.locked());
        assertEquals(1, first.lock().minutes());

        clock.advance(Duration.ofMinutes(1).plusSeconds(1));
        assertTrue(throttle.check(IP, ACCOUNT).isEmpty(), "süresi dolan kilit açılmalı");

        throttle.recordFailure(IP, ACCOUNT);
        LoginThrottle.FailureResult second = throttle.recordFailure(IP, ACCOUNT);
        assertTrue(second.locked());
        assertEquals(2, second.lock().minutes(), "ikinci kilit iki katı olmalı");
        assertEquals(2, second.lock().blockCount());
    }

    @Test
    void lockKeepsDoublingPastTheWindowWhenAttackerResumesRightAfterEachLock() {
        // Kilit pencereden (15 dk) uzun olunca, kilit biter bitmez gelen hata "sessiz pencere"
        // sayılıp seviyeyi düşürüyordu: kilit ~16 dakikada takılı kalıyordu (günde ~450 deneme).
        LoginThrottle throttle = new LoginThrottle(settings(5, 0), clock, null);
        long expected = 1;
        for (int cycle = 0; cycle < 12; cycle++) {
            LoginThrottle.FailureResult result = null;
            for (int i = 0; i < 5; i++) {
                result = throttle.recordFailure(IP, ACCOUNT);
            }
            assertTrue(result.locked(), "döngü " + cycle);
            assertEquals(Math.min(expected, 1440), result.lock().minutes(), "döngü " + cycle);
            expected *= 2;
            clock.advance(result.lock().remaining().plusSeconds(1));
        }
    }

    @Test
    void levelDropsOnlyAfterAQuietWindowFollowingTheLock() {
        LoginThrottle throttle = new LoginThrottle(settings(2, 0), clock, null);
        throttle.recordFailure(IP, ACCOUNT);
        assertEquals(1, throttle.recordFailure(IP, ACCOUNT).lock().blockCount());

        // Kilit (1 dk) bitti, sonra tam bir pencere (15 dk) hiç deneme yok: seviye bir kademe düşer.
        clock.advance(Duration.ofMinutes(1).plusMinutes(15).plusSeconds(2));
        throttle.recordFailure(IP, ACCOUNT);
        LoginThrottle.FailureResult next = throttle.recordFailure(IP, ACCOUNT);
        assertEquals(1, next.lock().blockCount(), "sessiz pencere sonrası seviye 0'dan yeniden 1'e");
        assertEquals(1, next.lock().minutes());
    }

    @Test
    void windowExpiryRefillsBudget() {
        LoginThrottle throttle = new LoginThrottle(settings(2, 0), clock, null);

        assertFalse(throttle.recordFailure(IP, ACCOUNT).locked());
        clock.advance(Duration.ofMinutes(16));
        assertFalse(throttle.recordFailure(IP, ACCOUNT).locked(),
                "pencere dolduktan sonra tek hata kilitlememeli");
    }

    @Test
    void resetAccountDoesNotClearIpBudget() {
        LoginThrottle throttle = new LoginThrottle(settings(3, 2), clock, null);

        throttle.recordFailure(IP, ACCOUNT); // hesap 1, IP 1
        LoginThrottle.FailureResult second = throttle.recordFailure(IP, ACCOUNT); // hesap 2, IP 2 → IP kilidi
        assertTrue(second.locked());
        assertEquals(LoginThrottle.ipKey(IP), second.lock().key());

        throttle.resetAccount(ACCOUNT);

        assertTrue(throttle.check(IP, ACCOUNT).isPresent(), "IP cezası başarılı girişle silinmemeli");

        clock.advance(Duration.ofMinutes(1).plusSeconds(1));
        assertTrue(throttle.check(IP, ACCOUNT).isEmpty());

        // Hesap sayacı gerçekten sıfırlandı: tek hata kilitlememeli.
        assertFalse(throttle.recordFailure(IP, ACCOUNT).locked());
    }

    @Test
    void adminResetClearsAccountAndIpAndSurvivesRestart() {
        FakeRepository repository = new FakeRepository();
        LoginThrottle throttle = new LoginThrottle(settings(2, 2), clock, repository);

        throttle.recordFailure(IP, ACCOUNT);
        assertTrue(throttle.recordFailure(IP, ACCOUNT).locked());
        assertTrue(throttle.check(IP, ACCOUNT).isPresent());
        assertTrue(repository.saved.containsKey(LoginThrottle.accountKey(ACCOUNT)));
        assertTrue(repository.saved.containsKey(LoginThrottle.ipKey(IP)));

        throttle.reset(ACCOUNT, List.of(IP));

        assertTrue(throttle.check(IP, ACCOUNT).isEmpty(), "yönetici kilidi hemen açmalı");
        assertFalse(repository.saved.containsKey(LoginThrottle.accountKey(ACCOUNT)));
        assertFalse(repository.saved.containsKey(LoginThrottle.ipKey(IP)));

        LoginThrottle restarted = new LoginThrottle(settings(2, 2), clock, repository);
        restarted.load(repository.saved.values());
        assertTrue(restarted.check(IP, ACCOUNT).isEmpty(), "kilit restart'tan sonra da açık kalmalı");
    }

    @Test
    void adminResetDeletesPersistedRowsEvenWhenNotCached() {
        FakeRepository repository = new FakeRepository();
        long now = clock.millis();
        repository.saved.put(LoginThrottle.accountKey(ACCOUNT),
                new LoginAttempt(LoginThrottle.accountKey(ACCOUNT), 0, now, now + 60_000, 1, now));
        repository.saved.put(LoginThrottle.ipKey(IP),
                new LoginAttempt(LoginThrottle.ipKey(IP), 0, now, now + 60_000, 1, now));

        // load() çağrılmadı: kayıtlar yalnızca veritabanında, bellekte değil.
        LoginThrottle throttle = new LoginThrottle(settings(3, 0), clock, repository);
        throttle.reset(ACCOUNT, List.of(IP));

        assertFalse(repository.saved.containsKey(LoginThrottle.accountKey(ACCOUNT)),
                "bellekte olmayan kalıcı hesap kilidi de silinmeli");
        assertFalse(repository.saved.containsKey(LoginThrottle.ipKey(IP)),
                "bellekte olmayan kalıcı IP kilidi de silinmeli");
    }

    @Test
    void resetIpClearsOnlyTheIpBudget() {
        FakeRepository repository = new FakeRepository();
        LoginThrottle throttle = new LoginThrottle(settings(3, 2), clock, repository);

        throttle.recordFailure(IP, ACCOUNT);
        assertTrue(throttle.recordFailure(IP, ACCOUNT).locked());
        throttle.resetIp(IP);

        assertTrue(throttle.check(IP, ACCOUNT).isEmpty());
        assertFalse(repository.saved.containsKey(LoginThrottle.ipKey(IP)));
        LoginThrottle.FailureResult third = throttle.recordFailure(IP, ACCOUNT);
        assertTrue(third.locked(), "hesap sayacı IP sıfırlamasından etkilenmemeli");
        assertEquals(LoginThrottle.accountKey(ACCOUNT), third.lock().key());
    }

    @Test
    void resetIpIgnoresNullAndBlankValues() {
        LoginThrottle throttle = new LoginThrottle(settings(3, 0), clock, null);
        assertDoesNotThrow(() -> {
            throttle.resetIp(null);
            throttle.resetIp("  ");
        });
    }

    @Test
    void ipThrottleIsOffWhenThresholdIsZero() {
        LoginThrottle throttle = new LoginThrottle(settings(5, 0), clock, null);

        for (int i = 0; i < 20; i++) {
            throttle.recordFailure(IP, UUID.randomUUID());
        }

        assertTrue(throttle.check(IP, null).isEmpty(), "ip-threshold: 0 iken IP kilidi olmamalı");
    }

    @Test
    void staleRowsAreIgnoredOnLoad() {
        long now = clock.millis();
        long old = now - Duration.ofHours(2).toMillis();
        LoginAttempt stale = new LoginAttempt(LoginThrottle.accountKey(ACCOUNT), 2, old, 0, 0, old);

        LoginThrottle throttle = new LoginThrottle(settings(3, 0), clock, null);
        throttle.load(List.of(stale));

        LoginThrottle.FailureResult result = throttle.recordFailure(IP, ACCOUNT);
        assertEquals(1, result.attempts(), "eski pencere yeni sayaca taşınmamalı");
    }

    @Test
    void lockedFailureReportsThresholdAsAttempts() {
        LoginThrottle throttle = new LoginThrottle(settings(2, 0), clock, null);
        throttle.recordFailure(IP, ACCOUNT);
        LoginThrottle.FailureResult locked = throttle.recordFailure(IP, ACCOUNT);
        assertTrue(locked.locked());
        assertEquals(2, locked.attempts(), "denetim günlüğü 0/2 değil 2/2 göstermeli");
    }

    @Test
    void lockDurationNeverOverflowsBelowMax() {
        // Çok büyük taban: base << shift taşmadan önce tavana sabitlenmeli.
        ThrottleSettings huge = new ThrottleSettings(Duration.ofMinutes(15), 1, 0,
                Duration.ofDays(200), Duration.ofDays(365));
        LoginThrottle throttle = new LoginThrottle(huge, clock, null);
        for (int i = 0; i < 25; i++) {
            LoginThrottle.FailureResult result = throttle.recordFailure(IP, ACCOUNT);
            assertTrue(result.locked());
            assertTrue(result.lock().remaining().compareTo(Duration.ofDays(200)) >= 0,
                    "kilit süresi tabanın altına düşmemeli (taşma)");
            clock.advance(result.lock().remaining().plusSeconds(1));
        }
    }

    @Test
    void verdictMinutesRoundUp() {
        assertEquals(1, new LoginThrottle.Verdict("k", Duration.ofSeconds(1), 1).minutes());
        assertEquals(1, new LoginThrottle.Verdict("k", Duration.ofSeconds(30), 1).minutes());
        assertEquals(2, new LoginThrottle.Verdict("k", Duration.ofSeconds(61), 1).minutes());
    }

    @Test
    void purgeStaleRemovesExpiredCounters() {
        FakeRepository repository = new FakeRepository();
        LoginThrottle throttle = new LoginThrottle(settings(5, 0), clock, repository);
        throttle.recordFailure(IP, ACCOUNT);
        assertEquals(1, throttle.cachedEntryCount());

        // Pencere dolduktan sonra hiçbir işe yaramayan sayaç bellekten silinmeli.
        clock.advance(Duration.ofMinutes(16));
        throttle.purgeStale();

        assertEquals(0, throttle.cachedEntryCount());
        assertTrue(repository.saved.isEmpty(), "kalıcı kayıt da temizlenmeli");
    }

    @Test
    void purgeStaleDecaysAndThenForgetsPenaltyHistory() {
        FakeRepository repository = new FakeRepository();
        LoginThrottle throttle = new LoginThrottle(settings(1, 0), clock, repository);
        LoginThrottle.FailureResult result = throttle.recordFailure(IP, ACCOUNT);
        assertTrue(result.locked());
        assertEquals(1, throttle.cachedEntryCount(), "yalnızca hesap sayacı tutulmalı");

        // Kilit ve geçmiş penceresi çoktan geçti: ceza seviyesi sıfırlanıp kayıt unutulmalı.
        clock.advance(Duration.ofHours(25));
        throttle.purgeStale();

        assertEquals(0, throttle.cachedEntryCount());
        assertTrue(repository.saved.isEmpty(), "ceza seviyesi sıfırlanınca kayıt silinmeli");
    }

    private static ThrottleSettings settings(int accountThreshold, int ipThreshold) {
        return new ThrottleSettings(Duration.ofMinutes(15), accountThreshold, ipThreshold,
                Duration.ofMinutes(1), Duration.ofHours(24));
    }

    private static final class MutableClock extends Clock {

        private Instant now;

        MutableClock(Instant start) {
            this.now = start;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static final class FakeRepository implements LoginAttemptRepository {

        private final Map<String, LoginAttempt> saved = new ConcurrentHashMap<>();

        @Override
        public CompletableFuture<List<LoginAttempt>> loadAll() {
            return CompletableFuture.completedFuture(new ArrayList<>(saved.values()));
        }

        @Override
        public void save(LoginAttempt attempt) {
            saved.put(attempt.key(), attempt);
        }

        @Override
        public void delete(String key) {
            saved.remove(key);
        }

        @Override
        public void purgeOlderThan(long epochMillis) {
            Collection<LoginAttempt> values = saved.values();
            values.removeIf(attempt -> attempt.updatedAt() < epochMillis);
        }
    }
}

package dev.nexus.login.totp;

import dev.nexus.login.config.DatabaseSettings;
import dev.nexus.login.database.DatabaseManager;
import dev.nexus.login.database.DatabaseType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

class TwoFactorServiceTest {

    private static final Executor DIRECT = Runnable::run;
    private final SecureRandom random = new SecureRandom();
    private final AtomicLong nowMs = new AtomicLong(1_700_000_000_000L);

    @TempDir
    Path dir;

    private DatabaseManager db;
    private TotpRepository repo;
    private TotpVault vault;

    @BeforeEach
    void setUp() {
        db = new DatabaseManager(new DatabaseSettings(DatabaseType.SQLITE, "nexus.db", "localhost", 3306, "x", "x", "",
                "disable", 4, 1, 5_000L, 1_800_000L), dir, DIRECT, Logger.getLogger("t"));
        db.start().join();
        repo = new TotpRepository(db, DIRECT);
        byte[] key = new byte[32];
        random.nextBytes(key);
        vault = new TotpVault(key, random);
    }

    @AfterEach
    void tearDown() {
        db.close();
    }

    private TwoFactorService service(TotpVault v) {
        return new TwoFactorService(repo, v, random, nowMs::get);
    }

    private String codeAt(String base32, long offsetSteps) {
        long step = Totp.step(nowMs.get() / 1000) + offsetSteps;
        return String.format("%06d", Totp.hotp(Base32.decode(base32), step, 6));
    }

    private String enroll(TwoFactorService tf, UUID uuid) {
        String secret = tf.beginEnroll(uuid).orElseThrow();
        assertEquals(TwoFactorService.Outcome.OK, tf.confirmEnroll(uuid, codeAt(secret, 0)).join());
        return secret;
    }

    @Test
    void enrollmentIsTwoPhaseAndNothingIsStoredBeforeConfirmation() {
        TwoFactorService tf = service(vault);
        UUID uuid = UUID.randomUUID();
        String secret = tf.beginEnroll(uuid).orElseThrow();
        assertEquals(32, secret.length());
        assertTrue(repo.find(uuid).join().isEmpty(), "onaysız kurulum DB'ye yazılmamalı");

        assertEquals(TwoFactorService.Outcome.WRONG, tf.confirmEnroll(uuid, "000000".equals(codeAt(secret, 0)) ? "111111" : "000000").join());
        assertTrue(repo.find(uuid).join().isEmpty());
        assertEquals(TwoFactorService.Outcome.OK, tf.confirmEnroll(uuid, codeAt(secret, 0)).join(), "aynı secret hâlâ bekliyor");

        TotpRepository.Entry entry = repo.find(uuid).join().orElseThrow();
        assertFalse(entry.secretEnc().contains(secret), "secret düz metin saklanmamalı");
        assertEquals(TwoFactorService.Outcome.REPLAYED, tf.verify(entry, codeAt(secret, 0)).join(),
                "kurulumda kullanılan kod girişte tekrar kullanılamaz");
    }

    @Test
    void verifyAcceptsNextCodeRejectsReplayAndOlderStep() {
        TwoFactorService tf = service(vault);
        UUID uuid = UUID.randomUUID();
        String secret = enroll(tf, uuid);

        nowMs.addAndGet(30_000);
        TotpRepository.Entry entry = repo.find(uuid).join().orElseThrow();
        assertEquals(TwoFactorService.Outcome.OK, tf.verify(entry, codeAt(secret, 0)).join());
        TotpRepository.Entry after = repo.find(uuid).join().orElseThrow();
        assertEquals(TwoFactorService.Outcome.REPLAYED, tf.verify(after, codeAt(secret, 0)).join(), "aynı kod ikinci kez");
        assertEquals(TwoFactorService.Outcome.REPLAYED, tf.verify(after, codeAt(secret, -1)).join(),
                "T kabul edildikten sonra T-1 de reddedilir");
        assertEquals(TwoFactorService.Outcome.REPLAYED, tf.verify(entry, codeAt(secret, 0)).join(),
                "eski Entry ile bile DB'deki CAS tekrarı engeller");
        assertEquals(TwoFactorService.Outcome.WRONG, tf.verify(after, codeAt(secret, 5)).join());
    }

    @Test
    void parallelUseOfTheSameCodeSucceedsExactlyOnce() throws Exception {
        TwoFactorService tf = service(vault);
        UUID uuid = UUID.randomUUID();
        String secret = enroll(tf, uuid);
        nowMs.addAndGet(60_000);
        TotpRepository.Entry entry = repo.find(uuid).join().orElseThrow();
        String code = codeAt(secret, 0);
        var pool = Executors.newFixedThreadPool(8);
        try {
            var futures = new java.util.ArrayList<CompletableFuture<TwoFactorService.Outcome>>();
            for (int i = 0; i < 8; i++) {
                futures.add(CompletableFuture.supplyAsync(() -> tf.verify(entry, code).join(), pool));
            }
            long ok = futures.stream().map(CompletableFuture::join).filter(o -> o == TwoFactorService.Outcome.OK).count();
            assertEquals(1, ok, "aynı kod paralelde yalnızca bir kez kabul edilmeli");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void failClosedWithoutKeyOrWithWrongKey() {
        UUID uuid = UUID.randomUUID();
        enroll(service(vault), uuid);
        TotpRepository.Entry entry = repo.find(uuid).join().orElseThrow();

        TwoFactorService noKey = service(null);
        assertFalse(noKey.available());
        assertTrue(noKey.beginEnroll(UUID.randomUUID()).isEmpty());
        assertEquals(TwoFactorService.Outcome.UNAVAILABLE, noKey.verify(entry, "123456").join());

        byte[] other = new byte[32];
        random.nextBytes(other);
        assertEquals(TwoFactorService.Outcome.UNAVAILABLE, service(new TotpVault(other, random)).verify(entry, "123456").join(),
                "yanlış anahtarla çözülemeyen kayıt kabul edilmez");
    }

    @Test
    void pendingEnrollmentExpiresAndCanBeCancelled() {
        TwoFactorService tf = service(vault);
        UUID uuid = UUID.randomUUID();
        String secret = tf.beginEnroll(uuid).orElseThrow();
        nowMs.addAndGet(TwoFactorService.PENDING_TTL_MS + 1);
        assertEquals(TwoFactorService.Outcome.UNAVAILABLE, tf.confirmEnroll(uuid, codeAt(secret, 0)).join());
        assertTrue(repo.find(uuid).join().isEmpty());

        String again = tf.beginEnroll(uuid).orElseThrow();
        tf.cancelEnroll(uuid);
        assertEquals(TwoFactorService.Outcome.UNAVAILABLE, tf.confirmEnroll(uuid, codeAt(again, 0)).join());
    }

    @Test
    void disableRemovesTheRowAndSecondInsertIsRejected() {
        TwoFactorService tf = service(vault);
        UUID uuid = UUID.randomUUID();
        enroll(tf, uuid);
        assertFalse(repo.insert(uuid, "v1$x", 0, 0).join(), "ikinci kayıt mevcut satırı ezmez");
        assertTrue(tf.disable(uuid).join());
        assertTrue(repo.find(uuid).join().isEmpty());
        assertFalse(tf.disable(uuid).join());
        assertTrue(repo.isEmpty().join());
    }
}

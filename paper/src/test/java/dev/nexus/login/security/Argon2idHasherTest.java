package dev.nexus.login.security;

import dev.nexus.login.config.HashingSettings;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class Argon2idHasherTest {

    // Testlerin hızlı olması için OWASP alt sınırındaki parametreler.
    private static final HashingSettings WEAK = new HashingSettings(19_456, 2, 1, 16, 32, 2);
    private static final HashingSettings STRONG = new HashingSettings(19_456, 3, 1, 16, 32, 2);
    private static final HashingSettings PEPPERED =
            new HashingSettings(19_456, 2, 1, 16, 32, 2, "unit-test-pepper-1234567890");

    private static ExecutorService executor;

    @BeforeAll
    static void setUp() {
        executor = Executors.newFixedThreadPool(2);
    }

    @AfterAll
    static void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void producesArgon2idPhcStringWithRandomSalt() {
        var hasher = new Argon2idHasher(WEAK, executor);

        String first = hasher.hash("Nexus123!".toCharArray()).join();
        String second = hasher.hash("Nexus123!".toCharArray()).join();

        assertTrue(first.startsWith("$argon2id$v=19$m=19456,t=2,p=1$"), first);
        assertNotEquals(first, second, "aynı şifre farklı salt ile farklı hash üretmeli");
    }

    @Test
    void verifiesCorrectPasswordAndRejectsWrongOne() {
        var hasher = new Argon2idHasher(WEAK, executor);
        String hash = hasher.hash("Nexus123!".toCharArray()).join();

        assertInstanceOf(VerificationResult.Match.class, hasher.verify(hash, "Nexus123!".toCharArray()).join());
        assertInstanceOf(VerificationResult.Mismatch.class, hasher.verify(hash, "nexus123!".toCharArray()).join());
    }

    @Test
    void flagsRehashWhenCostParametersIncrease() {
        String oldHash = new Argon2idHasher(WEAK, executor).hash("Nexus123!".toCharArray()).join();

        var result = new Argon2idHasher(STRONG, executor).verify(oldHash, "Nexus123!".toCharArray()).join();

        assertEquals(new VerificationResult.Match(true), result);
    }

    @Test
    void rejectsNonArgon2idHashes() {
        var hasher = new Argon2idHasher(WEAK, executor);

        assertInstanceOf(VerificationResult.Mismatch.class,
                hasher.verify("5f4dcc3b5aa765d61d8327deb882cf99", "password".toCharArray()).join());
        assertInstanceOf(VerificationResult.Mismatch.class,
                hasher.verify(null, "password".toCharArray()).join());
    }

    @Test
    void pepperIsRequiredToVerifyHash() {
        String hash = new Argon2idHasher(PEPPERED, executor).hash("Nexus123!".toCharArray()).join();

        assertInstanceOf(VerificationResult.Match.class, new Argon2idHasher(PEPPERED, executor)
                .verify(hash, "Nexus123!".toCharArray()).join());
        // Pepper'sız bir hasher aynı PIN'i doğrulayamaz: pepper gerçekten hesaba katılıyor.
        assertInstanceOf(VerificationResult.Mismatch.class, new Argon2idHasher(WEAK, executor)
                .verify(hash, "Nexus123!".toCharArray()).join());
    }

    @Test
    void rejectsAbsurdPhcParametersBeforeArgon2Runs() {
        var hasher = new Argon2idHasher(WEAK, executor);
        String bomb = "$argon2id$v=19$m=999999999,t=2,p=1$c2FsdHNhbHRzYWx0c2FsdA$"
                + "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

        assertInstanceOf(VerificationResult.Mismatch.class,
                hasher.verify(bomb, "Nexus123!".toCharArray()).join());
    }

    @Test
    void rejectsTooShortPepper() {
        assertThrows(IllegalArgumentException.class,
                () -> new HashingSettings(19_456, 2, 1, 16, 32, 2, "kisa"));
    }

    @Test
    void wipesPasswordArrayAfterUse() {
        var hasher = new Argon2idHasher(WEAK, executor);
        char[] password = "Nexus123!".toCharArray();

        hasher.hash(password).join();

        for (char c : password) {
            assertEquals('\0', c);
        }
    }
}

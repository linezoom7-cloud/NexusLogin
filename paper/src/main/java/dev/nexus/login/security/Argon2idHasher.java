package dev.nexus.login.security;

import de.mkammerer.argon2.Argon2;
import de.mkammerer.argon2.Argon2Factory;
import dev.nexus.login.config.HashingSettings;
import org.jetbrains.annotations.Nullable;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

/**
 * argon2-jvm (referans C implementasyonu, JNA üzerinden) ile Argon2id.
 *
 * <p>Üretilen değer PHC string formatındadır, örn.:
 * <pre>$argon2id$v=19$m=65536,t=3,p=1$&lt;salt-b64&gt;$&lt;hash-b64&gt;</pre>
 * Salt, maliyet parametreleri ve sürüm hash'in içinde saklandığı için ayrı sütun gerekmez ve
 * config değiştirildiğinde eski hash'ler doğrulanmaya devam eder.
 */
public final class Argon2idHasher implements PasswordHasher {

    private static final String ARGON2ID_PREFIX = "$argon2id$";
    private static final String PEPPER_ALGORITHM = "HmacSHA256";
    /**
     * DB'ye yazabilen bir saldırgan, doğrulama sırasında OOM tetiklemek için PHC string'ine
     * devasa {@code m=} değeri koyabilir. Bu üst sınırlar, meşru bir hash'i reddetmeyecek
     * kadar geniş ama belleği tüketmeyecek kadar dardır (256 MiB = varsayılanın 4 katı).
     */
    private static final int MAX_MEMORY_KIB = 262_144; // 256 MiB
    private static final int MAX_ITERATIONS = 64;
    private static final int MAX_PARALLELISM = 16;

    private final Argon2 argon2;
    private final HashingSettings settings;
    private final Executor executor;
    private volatile byte[] pepper;

    /**
     * @param cryptoExecutor SINIRLI boyutlu bir executor olmalı (bkz. NexusExecutors#crypto);
     *                       aynı anda çalışan hash sayısı = bellek tüketimi tavanı
     */
    public Argon2idHasher(HashingSettings settings, Executor cryptoExecutor) {
        this.settings = settings;
        this.executor = cryptoExecutor;
        this.argon2 = Argon2Factory.create(
                Argon2Factory.Argon2Types.ARGON2id, settings.saltLength(), settings.hashLength());
        this.pepper = settings.pepper().isEmpty()
                ? new byte[0]
                : settings.pepper().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public CompletableFuture<String> hash(char[] password) {
        return submitWiping(password, () -> {
            byte[] derived = applyPepper(password);
            try {
                return derived == null
                        ? argon2.hash(settings.iterations(), settings.memoryKib(),
                                settings.parallelism(), password)
                        : argon2.hash(settings.iterations(), settings.memoryKib(),
                                settings.parallelism(), derived);
            } finally {
                wipe(derived);
            }
        });
    }

    @Override
    public CompletableFuture<VerificationResult> verify(String storedHash, char[] password) {
        return submitWiping(password, () -> {
            // Yalnızca Argon2id kabul edilir: DB'ye enjekte edilmiş Argon2i/d veya bozuk bir değer
            // ile algoritma düşürme (downgrade) yapılamaz.
            if (storedHash == null || !storedHash.startsWith(ARGON2ID_PREFIX)
                    || !parametersWithinLimits(storedHash)) {
                return new VerificationResult.Mismatch();
            }
            byte[] derived = applyPepper(password);
            try {
                boolean matches = derived == null
                        ? argon2.verify(storedHash, password)
                        : argon2.verify(storedHash, derived);
                if (!matches) {
                    return new VerificationResult.Mismatch();
                }
                boolean needsRehash = argon2.needsRehash(
                        storedHash, settings.iterations(), settings.memoryKib(), settings.parallelism());
                return new VerificationResult.Match(needsRehash);
            } finally {
                wipe(derived);
            }
        });
    }

    /**
     * Açılışta {@link PepperFile} sonucunu uygular. Girişler hazırlık bitmeden kabul edilmediği
     * için bu, ilk hash'ten önce çağrılır.
     */
    public void usePepper(String value) {
        this.pepper = value.isEmpty() ? new byte[0] : value.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Pepper varsa PIN'i HMAC-SHA256 ile bağlar. Pepper yoksa {@code null} döner ve çağıran,
     * eski (pepper'sız) davranışı birebir koruyarak char[] yolunu kullanır.
     */
    private byte @Nullable [] applyPepper(char[] password) {
        byte[] pepper = this.pepper;
        if (pepper.length == 0) {
            return null;
        }
        try {
            Mac mac = Mac.getInstance(PEPPER_ALGORITHM);
            mac.init(new SecretKeySpec(pepper, PEPPER_ALGORITHM));
            byte[] raw = utf8Bytes(password);
            try {
                return mac.doFinal(raw);
            } finally {
                Arrays.fill(raw, (byte) 0);
            }
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Pepper (HMAC-SHA256) uygulanamadı", e);
        }
    }

    private static byte[] utf8Bytes(char[] password) {
        ByteBuffer encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(password));
        byte[] bytes = new byte[encoded.remaining()];
        encoded.get(bytes);
        // ByteBuffer'ın iç kopyası da şifre içeriyor; geri dönüşte silinir.
        if (encoded.hasArray()) {
            Arrays.fill(encoded.array(), (byte) 0);
        }
        return bytes;
    }

    private static void wipe(byte @Nullable [] data) {
        if (data != null) {
            Arrays.fill(data, (byte) 0);
        }
    }

    /**
     * PHC string'indeki {@code m}, {@code t} ve {@code p} değerlerini ayrıştırıp üst sınırlara
     * karşı doğrular. Ayrıştırılamayan bir string güvenli tarafta kalınarak reddedilir.
     */
    static boolean parametersWithinLimits(String storedHash) {
        int start = storedHash.indexOf("$m=");
        if (start < 0) {
            return false;
        }
        int end = storedHash.indexOf('$', start + 1);
        if (end < 0) {
            return false;
        }
        int memory = -1;
        int iterations = -1;
        int parallelism = -1;
        for (String part : storedHash.substring(start + 1, end).split(",")) {
            int eq = part.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = part.substring(0, eq);
            int value;
            try {
                value = Integer.parseInt(part.substring(eq + 1));
            } catch (NumberFormatException e) {
                return false;
            }
            switch (key) {
                case "m" -> memory = value;
                case "t" -> iterations = value;
                case "p" -> parallelism = value;
                default -> {
                    // Bilinmeyen parametre yok sayılır.
                }
            }
        }
        return memory >= 8 && memory <= MAX_MEMORY_KIB
                && iterations >= 1 && iterations <= MAX_ITERATIONS
                && parallelism >= 1 && parallelism <= MAX_PARALLELISM
                && memory >= 8 * parallelism;
    }

    @Override
    public CompletableFuture<Duration> benchmark() {
        char[] sample = "nexus-benchmark".toCharArray();
        long start = System.nanoTime();
        return hash(sample).thenApply(ignored -> Duration.ofNanos(System.nanoTime() - start));
    }

    /**
     * İşi crypto executor'üne gönderir ve şifre dizisini her koşulda siler — executor görevi
     * reddetse bile (ör. eklenti kapanırken).
     */
    private <T> CompletableFuture<T> submitWiping(char[] password, Supplier<T> work) {
        try {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return work.get();
                } finally {
                    argon2.wipeArray(password);
                }
            }, executor);
        } catch (RejectedExecutionException e) {
            Arrays.fill(password, '\0');
            return CompletableFuture.failedFuture(e);
        }
    }
}

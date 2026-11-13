package dev.nexus.login.security;

import de.mkammerer.argon2.Argon2;
import de.mkammerer.argon2.Argon2Factory;
import dev.nexus.login.config.HashingSettings;

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

    private final Argon2 argon2;
    private final HashingSettings settings;
    private final Executor executor;

    /**
     * @param cryptoExecutor SINIRLI boyutlu bir executor olmalı (bkz. NexusExecutors#crypto);
     *                       aynı anda çalışan hash sayısı = bellek tüketimi tavanı
     */
    public Argon2idHasher(HashingSettings settings, Executor cryptoExecutor) {
        this.settings = settings;
        this.executor = cryptoExecutor;
        this.argon2 = Argon2Factory.create(
                Argon2Factory.Argon2Types.ARGON2id, settings.saltLength(), settings.hashLength());
    }

    @Override
    public CompletableFuture<String> hash(char[] password) {
        return submitWiping(password, () ->
                argon2.hash(settings.iterations(), settings.memoryKib(), settings.parallelism(), password));
    }

    @Override
    public CompletableFuture<VerificationResult> verify(String storedHash, char[] password) {
        return submitWiping(password, () -> {
            // Yalnızca Argon2id kabul edilir: DB'ye enjekte edilmiş Argon2i/d veya bozuk bir değer
            // ile algoritma düşürme (downgrade) yapılamaz.
            if (storedHash == null || !storedHash.startsWith(ARGON2ID_PREFIX)) {
                return new VerificationResult.Mismatch();
            }
            if (!argon2.verify(storedHash, password)) {
                return new VerificationResult.Mismatch();
            }
            boolean needsRehash = argon2.needsRehash(
                    storedHash, settings.iterations(), settings.memoryKib(), settings.parallelism());
            return new VerificationResult.Match(needsRehash);
        });
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

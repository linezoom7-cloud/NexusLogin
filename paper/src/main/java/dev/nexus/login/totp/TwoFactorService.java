package dev.nexus.login.totp;

import org.jetbrains.annotations.Nullable;

import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * İki adımlı doğrulamanın iş kuralları (Bukkit'ten bağımsız, testlenebilir).
 * Kurulum iki aşamalıdır: secret yalnızca bellekte bekler, ilk geçerli kodla DB'ye yazılır.
 * Böylece "gösterildi ama onaylanmadı" yarım kayıt hiçbir zaman 2FA'yı etkinleştirmez.
 */
public final class TwoFactorService {

    public static final long PENDING_TTL_MS = 180_000;

    public enum Outcome { OK, WRONG, REPLAYED, UNAVAILABLE }

    private record Pending(byte[] secret, long expiresAt) {
    }

    private final TotpRepository repository;
    private final @Nullable TotpVault vault;
    private final SecureRandom random;
    private final LongSupplier clockMs;
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

    /** @param vault anahtar yüklenemediyse null: 2FA'lı hesaplar giriş yapamaz (fail-closed) */
    public TwoFactorService(TotpRepository repository, @Nullable TotpVault vault, SecureRandom random, LongSupplier clockMs) {
        this.repository = repository;
        this.vault = vault;
        this.random = random;
        this.clockMs = clockMs;
    }

    public boolean available() {
        return vault != null;
    }

    public CompletableFuture<Optional<TotpRepository.Entry>> lookup(UUID uuid) {
        return repository.find(uuid);
    }

    /** Giriş: kodu doğrular ve adımı atomik olarak tüketir (aynı kod ikinci kez geçmez). */
    public CompletableFuture<Outcome> verify(TotpRepository.Entry entry, String code) {
        if (vault == null) {
            return CompletableFuture.completedFuture(Outcome.UNAVAILABLE);
        }
        byte[] secret;
        try {
            secret = vault.decrypt(entry.uuid(), entry.secretEnc());
        } catch (TotpVault.VaultException e) {
            return CompletableFuture.completedFuture(Outcome.UNAVAILABLE);
        }
        long step = Totp.match(secret, code, clockMs.getAsLong() / 1000);
        Arrays.fill(secret, (byte) 0);
        if (step < 0) {
            return CompletableFuture.completedFuture(Outcome.WRONG);
        }
        if (step <= entry.lastStep()) {
            return CompletableFuture.completedFuture(Outcome.REPLAYED);
        }
        return repository.acceptStep(entry.uuid(), step).thenApply(ok -> ok ? Outcome.OK : Outcome.REPLAYED);
    }

    /** Kurulumu başlatır; dönen Base32 secret yalnızca oyuncunun menüsünde gösterilir. */
    public Optional<String> beginEnroll(UUID uuid) {
        if (vault == null) {
            return Optional.empty();
        }
        byte[] secret = Totp.newSecret(random);
        cancelEnroll(uuid);
        pending.put(uuid, new Pending(secret, clockMs.getAsLong() + PENDING_TTL_MS));
        return Optional.of(Base32.encode(secret));
    }

    public void cancelEnroll(UUID uuid) {
        Pending old = pending.remove(uuid);
        if (old != null) {
            Arrays.fill(old.secret(), (byte) 0);
        }
    }

    /**
     * Kurulumu onaylar. Onay kodunun adımı son kullanılan adım olarak yazılır. Süre dolduysa veya
     * bekleyen kurulum yoksa {@link Outcome#UNAVAILABLE}; kod yanlışsa kurulum bekler (aynı secret).
     */
    public CompletableFuture<Outcome> confirmEnroll(UUID uuid, String code) {
        Pending p = pending.get(uuid);
        long now = clockMs.getAsLong();
        if (vault == null || p == null || now > p.expiresAt()) {
            cancelEnroll(uuid);
            return CompletableFuture.completedFuture(Outcome.UNAVAILABLE);
        }
        long step = Totp.match(p.secret(), code, now / 1000);
        if (step < 0) {
            return CompletableFuture.completedFuture(Outcome.WRONG);
        }
        String sealed = vault.encrypt(uuid, p.secret());
        cancelEnroll(uuid);
        return repository.insert(uuid, sealed, step, now).thenApply(ok -> ok ? Outcome.OK : Outcome.UNAVAILABLE);
    }

    public CompletableFuture<Boolean> disable(UUID uuid) {
        cancelEnroll(uuid);
        return repository.delete(uuid);
    }

    /** Süresi dolmuş bekleyen kurulumları temizler (periyodik). */
    public void purgeExpired() {
        long now = clockMs.getAsLong();
        pending.entrySet().removeIf(e -> {
            if (now > e.getValue().expiresAt()) {
                Arrays.fill(e.getValue().secret(), (byte) 0);
                return true;
            }
            return false;
        });
    }
}

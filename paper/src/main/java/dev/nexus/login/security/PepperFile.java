package dev.nexus.login.security;

import org.jetbrains.annotations.Nullable;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.logging.Logger;

/**
 * Picks the Argon2 pepper at startup and, on a fresh install, creates one.
 *
 * <p>Order: {@code security.argon2.pepper} in config.yml, then {@code plugins/NexusLogin/pepper.key}.
 * When neither exists and the database has no accounts yet, a random pepper is written to
 * pepper.key. A short fingerprint (HMAC of a fixed text, only for generated peppers) is kept in
 * {@code nexus_meta} so a lost, replaced or per-server-different pepper.key is refused (fail-closed)
 * instead of turning every PIN into "wrong PIN".
 *
 * <p>Türkçe: pepper değişirse tüm PIN'ler geçersiz olur. Bu yüzden yanlış/eksik anahtarla açılmak
 * yerine eklenti güvenlik kilidine girer ve ne yapılacağını loglar.
 */
public final class PepperFile {

    public static final String FILE = "pepper.key";
    public static final String META_KEY = "pepper_check";
    private static final int GENERATED_BYTES = 32;

    /** nexus_meta'daki parmak izi satırı (bloklayan çağrılar; G/Ç thread'inde kullanılır). */
    public interface Meta {
        @Nullable String get();

        /** Satır yoksa yazar; her durumda saklanan değeri döndürür (yarışı kazanan değer). */
        String putIfAbsent(String value);
    }

    private PepperFile() {
    }

    /**
     * @return the pepper to use; empty string means "no pepper" (old installs with accounts).
     * @throws IllegalStateException when starting would make existing PINs unusable
     */
    public static String resolve(Path folder, String configured, boolean hasAccounts, Meta meta,
                                 SecureRandom random, Logger logger) throws IOException {
        String fromFile = load(folder);
        String stored = meta.get();
        if (!configured.isEmpty()) {
            if (stored != null && !stored.equals(fingerprint(configured))) {
                throw new IllegalStateException("security.argon2.pepper, hesapların oluşturulduğu pepper.key ile aynı "
                        + "değil; tüm PIN'ler geçersiz olurdu. config'teki pepper'ı boşaltın (pepper.key kullanılır).");
            }
            if (fromFile != null) {
                logger.warning(FILE + " yok sayıldı: config.yml'deki security.argon2.pepper kullanılıyor.");
            }
            return configured;
        }
        if (fromFile != null) {
            if (stored == null && hasAccounts) {
                throw new IllegalStateException(FILE + " bulundu ama mevcut hesaplar pepper'sız oluşturulmuş; "
                        + "bu anahtarla hiçbir PIN doğrulanamaz. " + FILE + " dosyasını kaldırın.");
            }
            requireSame(meta.putIfAbsent(fingerprint(fromFile)), fromFile);
            return fromFile;
        }
        if (stored != null) {
            throw new IllegalStateException(FILE + " eksik ama bu veritabanındaki hesaplar onunla oluşturulmuş. "
                    + "Yedekten geri yükleyin veya (Velocity ağı) auth sunucusundaki " + FILE + " dosyasını kopyalayın.");
        }
        if (hasAccounts) {
            logger.warning("security.argon2.pepper boş: veritabanı sızarsa PIN hash'leri pepper olmadan çevrimdışı "
                    + "kırılabilir. Mevcut hesaplar olduğu için otomatik pepper ÜRETİLMEDİ (eski PIN'ler bozulurdu).");
            return "";
        }
        String generated = generate(folder, random);
        requireSame(meta.putIfAbsent(fingerprint(generated)), generated);
        logger.warning("Yeni pepper üretildi: " + folder.resolve(FILE) + ". Kaybolursa HİÇBİR PIN doğrulanamaz; "
                + "veritabanından AYRI bir yere yedekleyin. Velocity ağında her sunucuya aynı dosyayı kopyalayın.");
        return generated;
    }

    private static void requireSame(String stored, String pepper) {
        if (!stored.equals(fingerprint(pepper))) {
            throw new IllegalStateException(FILE + " bu veritabanının kullandığı pepper ile aynı değil (başka sunucu "
                    + "farklı bir anahtar üretmiş olabilir). Doğru " + FILE + " dosyasını kopyalayın.");
        }
    }

    static @Nullable String load(Path folder) throws IOException {
        Path file = folder.resolve(FILE);
        if (!Files.exists(file)) {
            return null;
        }
        String value = Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                .map(String::strip).filter(l -> !l.isEmpty() && !l.startsWith("#")).findFirst().orElse(null);
        if (value == null || value.length() < dev.nexus.login.config.HashingSettings.MIN_PEPPER_LENGTH) {
            throw new IOException(FILE + " boş veya çok kısa");
        }
        return value;
    }

    private static String generate(Path folder, SecureRandom random) throws IOException {
        byte[] raw = new byte[GENERATED_BYTES];
        random.nextBytes(raw);
        String pepper = Base64.getEncoder().encodeToString(raw);
        Path file = folder.resolve(FILE);
        Files.createDirectories(folder);
        String content = "# NexusLogin pepper. Kaybolursa HİÇBİR PIN doğrulanamaz; güvenli bir yere yedekleyin.\n"
                + "# Veritabanı yedeğiyle AYNI yerde saklamayın. Velocity ağında her sunucuda aynı olmalı.\n"
                + pepper + "\n";
        try {
            Files.writeString(file, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        } catch (FileAlreadyExistsException e) {
            String existing = load(folder);
            if (existing == null) {
                throw new IOException(FILE + " var ama okunamadı", e);
            }
            return existing;
        }
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException windows) {
            // Windows: POSIX izinleri yok.
        }
        return pepper;
    }

    /** 128-bit fingerprint; a 256-bit random pepper cannot be guessed from it. */
    static String fingerprint(String pepper) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(pepper.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] out = mac.doFinal("nexuslogin-pepper-check-v1".getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(out, 0, 16);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}

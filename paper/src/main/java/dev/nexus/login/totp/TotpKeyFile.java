package dev.nexus.login.totp;

import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * TOTP ana anahtarı. Öncelik: ortam değişkeni {@code NEXUSLOGIN_TOTP_KEY} (base64), sonra
 * {@code plugins/NexusLogin/totp.key}. config.yml'ye yazılmaz (config yedeklerle birlikte dolaşır).
 */
public final class TotpKeyFile {

    public static final String ENV = "NEXUSLOGIN_TOTP_KEY";
    public static final String FILE = "totp.key";

    private TotpKeyFile() {
    }

    /** @return anahtar; hiçbir kaynakta yoksa null. Bozuk anahtar istisna fırlatır (sessizce yok sayılmaz). */
    public static byte @Nullable [] load(Path dataFolder, @Nullable String envValue) throws IOException {
        String raw = envValue != null && !envValue.isBlank() ? envValue : null;
        Path file = dataFolder.resolve(FILE);
        if (raw == null && Files.exists(file)) {
            raw = Files.readAllLines(file, StandardCharsets.UTF_8).stream()
                    .map(String::strip).filter(l -> !l.isEmpty() && !l.startsWith("#")).findFirst().orElse(null);
        }
        if (raw == null) {
            return null;
        }
        byte[] key;
        try {
            key = Base64.getDecoder().decode(raw.strip());
        } catch (IllegalArgumentException e) {
            throw new IOException("TOTP anahtarı geçersiz base64", e);
        }
        if (key.length < TotpVault.KEY_BYTES) {
            throw new IOException("TOTP anahtarı en az " + TotpVault.KEY_BYTES + " bayt olmalı");
        }
        return key;
    }

    /** Yeni anahtar üretip dosyaya yazar. Yalnızca hiç 2FA kaydı yokken çağrılmalı. */
    public static byte[] generate(Path dataFolder, SecureRandom random) throws IOException {
        byte[] key = new byte[TotpVault.KEY_BYTES];
        random.nextBytes(key);
        Path file = dataFolder.resolve(FILE);
        Files.createDirectories(dataFolder);
        String content = "# NexusLogin TOTP anahtarı. Kaybolursa TÜM 2FA kayıtları kullanılamaz; güvenli bir yere yedekleyin.\n"
                + "# Veritabanı yedeğiyle AYNI yerde saklamayın.\n" + Base64.getEncoder().encodeToString(key) + "\n";
        try {
            Files.writeString(file, content, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        } catch (FileAlreadyExistsException e) {
            byte[] existing = load(dataFolder, null);
            if (existing == null) {
                throw new IOException("totp.key var ama okunamadı", e);
            }
            return existing;
        }
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException windows) {
            // Windows: POSIX izinleri yok.
        }
        return key;
    }
}

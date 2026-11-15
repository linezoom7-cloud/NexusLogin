package dev.nexus.login.security;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Şifre hashleme sözleşmesi.
 *
 * <p><b>Sahiplik kuralı:</b> {@code char[] password} parametresi metoda devredilir ve iş bittiğinde
 * (başarılı ya da hatalı) sıfırlarla ezilir. Çağıran taraf diziyi sonrasında kullanmamalıdır.
 * {@code String} kullanılmaz çünkü String'ler immutable'dır ve GC'ye kadar heap'te düz metin kalır.
 */
public interface PasswordHasher {

    CompletableFuture<String> hash(char[] password);

    CompletableFuture<VerificationResult> verify(String storedHash, char[] password);

    /** Native kütüphaneyi önceden yükler ve tek bir hash'in süresini ölçer (parametre ayarı için). */
    CompletableFuture<Duration> benchmark();
}

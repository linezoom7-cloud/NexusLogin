package dev.nexus.login.security;

/**
 * Şifre doğrulama sonucu. Sealed hiyerarşi sayesinde çağıran taraf
 * {@code switch (result) { case Match m -> ...; case Mismatch x -> ...; }} yazdığında
 * derleyici tüm durumların ele alındığını garanti eder.
 */
public sealed interface VerificationResult {

    /**
     * @param needsRehash hash config'teki güncel maliyet parametrelerinden daha zayıf üretilmişse
     *                    {@code true}; giriş başarılıyken yeni parametrelerle yeniden hash'lenip kaydedilmeli
     */
    record Match(boolean needsRehash) implements VerificationResult {
    }

    record Mismatch() implements VerificationResult {
    }
}

package dev.nexus.login.auth;

public enum AuthState {
    /** Hesap bilgisi veritabanından yükleniyor. Oyuncu zaten kısıtlı. */
    LOADING,
    /** Giriş/kayıt bekleniyor. Tüm etkileşimler engelli. */
    UNAUTHENTICATED,
    /** Oyuna dahil. */
    AUTHENTICATED;

    public boolean isRestricted() {
        return this != AUTHENTICATED;
    }
}

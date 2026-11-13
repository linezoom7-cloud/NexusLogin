package dev.nexus.login.auth;

/**
 * Kalıcı kaba kuvvet sayacının tek satırlık durumu.
 *
 * <p>Kayıt veritabanında tutulur; böylece oyuncu yeniden bağlanınca veya sunucu yeniden
 * başlayınca sayaç sıfırlanmaz. {@code key} iki türlü olur:
 * <ul>
 *   <li>{@code ip:<adres>} — aynı ağdan gelen denemeler</li>
 *   <li>{@code acct:<uuid>} — belirli bir hesaba yönelik denemeler</li>
 * </ul>
 *
 * @param key         benzersiz sayaç anahtarı
 * @param attempts    mevcut penceredeki hatalı deneme sayısı
 * @param windowStart pencerenin başlangıcı (epoch ms)
 * @param lockedUntil kilidin bitişi (epoch ms; 0 = kilit yok)
 * @param blockCount  tamamlanmış kilit sayısı (üstel ceza seviyesi)
 * @param updatedAt   son güncelleme (epoch ms) — eski bir yazımın yenisini ezmesini engeller
 */
public record LoginAttempt(
        String key,
        int attempts,
        long windowStart,
        long lockedUntil,
        int blockCount,
        long updatedAt
) {
}

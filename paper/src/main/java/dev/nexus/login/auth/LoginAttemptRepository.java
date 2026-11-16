package dev.nexus.login.auth;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Kaba kuvvet sayaçlarının kalıcılığı. Okuma başlangıçta bir kez yapılır; yazımlar
 * ateşle-unut biçimindedir ve tek bir sıralı iş parçacığında tamamlanır.
 */
public interface LoginAttemptRepository {

    /** Tüm sayaçları yükler (eklenti açılışında, giriş kabul edilmeden önce). */
    CompletableFuture<List<LoginAttempt>> loadAll();

    /**
     * Yalnızca verilen anahtarların güncel satırlarını okur. Ortak veritabanında başka bir
     * auth sunucusunun yazdığı sayaçları görmek için kullanılır.
     */
    CompletableFuture<List<LoginAttempt>> load(java.util.Collection<String> keys);

    /** Güncel durumu yazar. Çağıran thread'i bloklamaz. */
    void save(LoginAttempt attempt);

    /** Başarılı girişte hesap anahtarını siler. Çağıran thread'i bloklamaz. */
    void delete(String key);

    /** Uzun süredir güncellenmeyen ve kilidi bitmiş kayıtları temizler. */
    void purgeOlderThan(long epochMillis);
}

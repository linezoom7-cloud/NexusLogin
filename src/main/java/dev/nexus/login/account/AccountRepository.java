package dev.nexus.login.account;

import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Hesap kalıcılığı. Tüm metotlar bloklamaz ve G/Ç executor'ünde tamamlanır;
 * sonuçla Bukkit API'sine dokunulacaksa {@code thenAcceptAsync(..., executors.mainThread())} kullanılmalıdır.
 */
public interface AccountRepository {

    CompletableFuture<Optional<Account>> findByUuid(UUID uuid);

    /** Büyük/küçük harf duyarsız arama (SQLite: COLLATE NOCASE, MariaDB: *_ci collation). */
    CompletableFuture<Optional<Account>> findByName(String playerName);

    CompletableFuture<Account> create(UUID uuid, String playerName, @Nullable String passwordHash,
                                      boolean premium, @Nullable String ip);

    CompletableFuture<Boolean> updatePasswordHash(UUID uuid, String passwordHash);

    /** Başarılı girişte çağrılır: son IP'yi ve (Mojang'da değişmiş olabilecek) oyuncu adını günceller. */
    CompletableFuture<Boolean> updateLastLogin(UUID uuid, String playerName, String ip);

    CompletableFuture<Boolean> setPremium(UUID uuid, boolean premium);

    CompletableFuture<Boolean> delete(UUID uuid);

    /**
     * Online-mode'da Mojang isimleri yeniden kullanılabilir: ismini değiştiren eski bir oyuncunun
     * satırı, aynı ismi yeni alan farklı UUID'li oyuncunun kaydını (unique isim indeksi yüzünden)
     * engellerdi. Bu metot, {@code exceptUuid} dışındaki satırlarda o ismi benzersiz bir yer
     * tutucuyla değiştirir; eski oyuncu tekrar girince adı {@code updateLastLogin} ile düzelir.
     *
     * @return ismi serbest bırakılan satır sayısı
     */
    CompletableFuture<Integer> releaseName(String playerName, UUID exceptUuid);

    /** Bu IP'den kayıt olmuş hesap sayısı (IP başına hesap limiti için). */
    CompletableFuture<Integer> countByRegistrationIp(String ip);
}

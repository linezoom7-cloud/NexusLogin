package dev.nexus.login.proxy;

import org.bukkit.entity.Player;

/** AuthService'in proxy'ye ihtiyaç duyduğu tek işlem; tek sunucu modunda yoktur (null). */
public interface ProxyLink {

    /** PIN/kayıt/forcelogin başarılı: proxy'ye imzalı AUTH_OK gönder. */
    void sendAuthOk(Player player, ProxyContext context);

    /** Oyuncu çıktı: bu bağlantının tekrar-oynatma kaydı silinsin (aksi halde kapasite dolar). */
    void forget(ProxyContext context);
}

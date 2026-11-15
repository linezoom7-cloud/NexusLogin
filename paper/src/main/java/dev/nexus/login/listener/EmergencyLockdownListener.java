package dev.nexus.login.listener;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Fail-Closed Güvenlik Kilidi.
 *
 * <p>Eklenti açılışında veritabanı, config veya şifreleme başlatılamazsa, oyuncuların
 * şifresiz/PIN'siz sunucuya girmesini engellemek için tüm bağlantıları anında reddeder.
 *
 * <p>Hatanın ayrıntısı (veritabanı adresi, kullanıcı adı, dosya yolu içerebilir) oyunculara
 * GÖSTERİLMEZ; yalnızca konsola yazılır.
 */
public final class EmergencyLockdownListener implements Listener {

    /** Oyuncuya gösterilen genel ekran; iç hata ayrıntısı içermez. */
    public static final Component KICK_MESSAGE = Component.text()
            .append(Component.text("✦ NexusLogin — Güvenlik Kilidi ✦", NamedTextColor.RED))
            .append(Component.newline()).append(Component.newline())
            .append(Component.text("Kimlik doğrulama sistemi başlatılamadı.", NamedTextColor.GRAY))
            .append(Component.newline())
            .append(Component.text("Güvenlik nedeniyle girişler kapalı. Lütfen yöneticinize başvurun.",
                    NamedTextColor.GRAY))
            .build();

    private final String reason;

    public EmergencyLockdownListener(String reason) {
        this.reason = reason == null ? "Bilinmeyen açılış hatası" : reason;
    }

    public String reason() {
        return reason;
    }

    /**
     * HIGHEST: daha sonra çalışan başka bir eklenti bağlantıyı yeniden "allow" yapamasın.
     * (MONITOR'da sonucu değiştirmek API sözleşmesine aykırıdır.)
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, KICK_MESSAGE);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        event.getPlayer().kick(KICK_MESSAGE);
    }
}

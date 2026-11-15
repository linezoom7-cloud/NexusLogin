package dev.nexus.login.listener;

import net.kyori.adventure.text.Component;
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
 */
public final class EmergencyLockdownListener implements Listener {

    private final String reason;

    public EmergencyLockdownListener(String reason) {
        this.reason = reason == null ? "Bilinmeyen açılış hatası" : reason;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                Component.text("§c✦ NexusLogin — Güvenlik Kilidi (Lockdown) ✦\n\n"
                        + "§7Kimlik doğrulama sistemi başlatılamadı:\n§c" + reason + "\n\n"
                        + "§7Güvenlik nedeniyle sunucu kilitlendi. Lütfen yöneticinize başvurun."));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        event.getPlayer().kick(Component.text("§c[NexusLogin] Güvenlik Kilidi: Giriş sistemi başlatılamadı."));
    }
}

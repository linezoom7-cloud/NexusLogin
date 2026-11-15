package dev.nexus.login.proxy;

import dev.nexus.login.auth.AuthService;
import dev.nexus.login.sync.SyncCodec;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.NotNull;

import java.util.logging.Logger;

/**
 * {@code nexuslogin:sync} kanalının Paper ucu. Mesajı doğrulayıp {@link AuthService}'e iletir ve
 * AUTH_OK gönderir. Doğrulanamayan her mesaj yok sayılır; oyuncu kilitli kalır (fail-closed).
 */
public final class ProxyBridge implements PluginMessageListener, ProxyLink {

    private final Plugin plugin;
    private final Logger logger;
    private final ProxyInbound inbound;
    private final AuthService auth;

    public ProxyBridge(Plugin plugin, ProxyInbound inbound, AuthService auth) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.inbound = inbound;
        this.auth = auth;
    }

    public void register() {
        // Lockdown sonrası yeniden başlatmada eski köprü (eski AuthService) dinlemeye devam etmesin.
        plugin.getServer().getMessenger().unregisterIncomingPluginChannel(plugin, SyncCodec.CHANNEL);
        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, SyncCodec.CHANNEL);
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, SyncCodec.CHANNEL, this);
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, SyncCodec.CHANNEL);
    }

    @Override
    public void onPluginMessageReceived(@NotNull String channel, @NotNull Player player, byte @NotNull [] data) {
        if (!SyncCodec.CHANNEL.equals(channel)) {
            return;
        }
        ProxyInbound.Result result = inbound.accept(data, player.getUniqueId());
        if (!result.accepted()) {
            // BAD_SIGNATURE çoğunlukla velocity.server-name veya anahtar dosyası uyuşmazlığıdır.
            logger.warning(() -> "Proxy mesajı reddedildi (" + result.rejection() + "): " + player.getName()
                    + ". velocity.server-name ve nexuslogin-sync.key proxy ile aynı mı?");
            return;
        }
        auth.onProxyState(player, result.context());
    }

    @Override
    public void sendAuthOk(Player player, ProxyContext context) {
        player.sendPluginMessage(plugin, SyncCodec.CHANNEL, inbound.authOk(player.getUniqueId(), context));
    }

    @Override
    public void forget(ProxyContext context) {
        inbound.forget(context.connectionId());
    }
}

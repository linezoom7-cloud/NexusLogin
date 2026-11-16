package dev.nexus.login.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.command.CommandExecuteEvent;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.connection.PostLoginEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.player.ServerPreConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import dev.nexus.login.sync.Direction;
import dev.nexus.login.sync.ReplayGuard;
import dev.nexus.login.sync.StatePayload;
import dev.nexus.login.sync.SyncCodec;
import dev.nexus.login.sync.SyncException;
import dev.nexus.login.sync.SyncKeys;
import dev.nexus.login.sync.SyncMessage;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Proxy kapı bekçisi. Doğrulanmamış oyuncu yalnızca auth sunucularında bulunabilir; AUTHED olmak için
 * auth sunucusunun imzalı AUTH_OK mesajı gerekir. Her hata yolu oyuncuyu kilitli bırakır (fail-closed).
 */
@Plugin(id = "nexuslogin", name = "NexusLogin", version = "1.1.0",
        description = "NexusLogin proxy gatekeeper", authors = {"Nexus Development"})
public final class NexusLoginVelocity {

    static final MinecraftChannelIdentifier CHANNEL = MinecraftChannelIdentifier.from(SyncCodec.CHANNEL);

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    private final ProxyAuthStates states = new ProxyAuthStates();
    private final ReplayGuard replayGuard = new ReplayGuard();
    private volatile ProxySettings settings;
    private volatile SyncKeys keys;

    @Inject
    public NexusLoginVelocity(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onInit(ProxyInitializeEvent event) {
        try {
            settings = ProxySettings.load(dataDirectory);
            if (SyncKeyFile.ensureExists(dataDirectory)) {
                logger.warn("Yeni sync anahtarı üretildi: {}. Bu dosyayı her NexusLogin backend'ine kopyalayın.",
                        dataDirectory.resolve(SyncKeyFile.NAME));
            }
            keys = SyncKeyFile.load(dataDirectory);
        } catch (Exception e) {
            // Fail-closed: ayar/anahtar yoksa kimse auth sunucusu dışına geçemez (settings null → hep red).
            logger.error("NexusLogin başlatılamadı; oyuncular auth sunucusundan çıkamayacak.", e);
            return;
        }
        // Kayıt zorunlu: kaydedilmeyen kanalda istemci mesajı backend'e olduğu gibi iletilir (kapı testi).
        proxy.getChannelRegistrar().register(CHANNEL);
        for (String name : settings.authServers()) {
            if (proxy.getServer(name).isEmpty()) {
                logger.warn("auth-servers içindeki '{}' velocity.toml'da tanımlı değil.", name);
            }
        }
        proxy.getScheduler().buildTask(this, this::heartbeat)
                .repeat(settings.heartbeat().toSeconds(), TimeUnit.SECONDS).schedule();
        logger.info("NexusLogin kapı bekçisi aktif. Auth sunucuları: {}", settings.authServers());
    }

    private boolean ready() {
        return settings != null && keys != null;
    }

    private boolean isAuthServer(String name) {
        return settings != null && settings.authServers().contains(name);
    }

    // ── bağlantı yaşam döngüsü ──

    @Subscribe(priority = Short.MAX_VALUE)
    public void onPostLogin(PostLoginEvent event) {
        Player player = event.getPlayer();
        ProxyAuthStates.Entry entry = states.start(player.getUniqueId(), player.isOnlineMode());
        if (!player.isActive()) {
            states.end(player.getUniqueId(), entry.connectionId); // kopma, olay sırasından önce geldi
        }
    }

    @Subscribe(priority = Short.MIN_VALUE)
    public void onDisconnect(DisconnectEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        states.get(uuid).flatMap(e -> states.end(uuid, e.connectionId))
                .ifPresent(e -> replayGuard.forget(e.connectionId));
    }

    @Subscribe(priority = Short.MIN_VALUE)
    public void onChooseInitialServer(PlayerChooseInitialServerEvent event) {
        Optional<RegisteredServer> auth = ready()
                ? settings.authServers().stream().map(proxy::getServer).flatMap(Optional::stream).findFirst()
                : Optional.empty();
        if (auth.isEmpty()) {
            event.setInitialServer(null);
            event.getPlayer().disconnect(Component.text(settings == null
                    ? "Login server is not available." : settings.kickNoAuthServer()));
            return;
        }
        event.setInitialServer(auth.get());
    }

    // LAST: başka bir eklentinin hedefi değiştirmesinden sonra son sözü kapı söyler.
    @Subscribe(priority = Short.MIN_VALUE)
    public void onPreConnect(ServerPreConnectEvent event) {
        Optional<RegisteredServer> target = event.getResult().getServer();
        if (target.isEmpty()) {
            return;
        }
        Player player = event.getPlayer();
        boolean authed = ready() && states.isAuthed(player.getUniqueId());
        String current = player.getCurrentServer().map(s -> s.getServerInfo().getName()).orElse(null);
        if (!ready() || !GateRules.mayConnect(authed, target.get().getServerInfo().getName(), settings.authServers(), current)) {
            event.setResult(ServerPreConnectEvent.ServerResult.denied());
            if (player.getCurrentServer().isEmpty()) {
                player.disconnect(Component.text(settings == null ? "Login server is not available." : settings.kickNoAuthServer()));
            } else if (settings != null) {
                player.sendMessage(Component.text(settings.denyMessage()));
            }
        }
    }

    @Subscribe(priority = Short.MAX_VALUE)
    public void onCommand(CommandExecuteEvent event) {
        CommandSource source = event.getCommandSource();
        if (!(source instanceof Player player)) {
            return;
        }
        boolean authed = ready() && states.isAuthed(player.getUniqueId());
        if (!ready() || !GateRules.mayRunCommand(authed, event.getCommand(), settings.allowedCommands())) {
            event.setResult(CommandExecuteEvent.CommandResult.denied());
            if (settings != null) {
                player.sendMessage(Component.text(settings.denyMessage()));
            }
        }
    }

    /**
     * Doğrulanmamış oyuncu auth sunucusundan atıldıysa (kilit, zaman aşımı, yanlış isim) yedek sunucuya
     * geçilmez ve oyuncu auth sunucusunun KENDİ mesajını görür. Aksi halde Velocity "try" listesindeki
     * sonraki sunucuyu dener, kapı bunu reddeder ve oyuncu gerçek sebebi göremezdi.
     */
    @Subscribe(priority = Short.MIN_VALUE)
    public void onKicked(KickedFromServerEvent event) {
        if (ready() && states.isAuthed(event.getPlayer().getUniqueId())) {
            return;
        }
        Component reason = event.getServerKickReason()
                .orElse(Component.text(settings == null ? "Login server is not available." : settings.kickNoAuthServer()));
        event.setResult(KickedFromServerEvent.DisconnectPlayer.create(reason));
    }

    @Subscribe
    public void onPostConnect(ServerPostConnectEvent event) {
        pushState(event.getPlayer());
    }

    // ── sync kanalı ──

    @Subscribe(priority = Short.MAX_VALUE)
    public void onPluginMessage(PluginMessageEvent event) {
        if (!event.getIdentifier().equals(CHANNEL)) {
            return;
        }
        // Hiçbir yönde ham iletim yok: istemci mesajı backend'e, backend mesajı istemciye ulaşmaz.
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        if (!(event.getSource() instanceof ServerConnection server) || !ready()
                || !isAuthServer(server.getServerInfo().getName())) {
            return;
        }
        handleBackendMessage(server, event.getData());
    }

    private void handleBackendMessage(ServerConnection server, byte[] data) {
        String serverName = server.getServerInfo().getName();
        Player player = server.getPlayer();
        SyncMessage message;
        try {
            message = SyncCodec.decode(data, keys, Direction.BACKEND_TO_PROXY, serverName);
            replayGuard.check(message);
        } catch (SyncException e) {
            logger.warn("Reddedilen sync mesajı ({}): {} sunucusundan, oyuncu {}", e.reason(), serverName, player.getUsername());
            return;
        }
        // Taşıyıcı bağlantı ile hedef oyuncu aynı olmalı: başka oyuncu adına AUTH_OK kabul edilmez.
        if (!message.playerUuid().equals(player.getUniqueId())) {
            logger.warn("Sync mesajı başka bir oyuncuya ait: {} sunucusu, taşıyıcı {}", serverName, player.getUsername());
            return;
        }
        if (message.op() != SyncMessage.Op.AUTH_OK) {
            return;
        }
        if (!states.acceptAuthOk(player.getUniqueId(), message.connectionId(), message.epoch())) {
            logger.warn("AUTH_OK yok sayıldı (eski bağlantı/epoch veya zaten doğrulanmış): {} sunucusu, oyuncu {}",
                    serverName, player.getUsername());
            return;
        }
        logger.info("{} doğrulandı ({} sunucusu).", player.getUsername(), serverName);
        afterLoginServer().ifPresentOrElse(
                target -> player.createConnectionRequest(target).fireAndForget(),
                () -> logger.warn("Giriş sonrası gidilecek sunucu bulunamadı; {} auth sunucusunda kaldı.", player.getUsername()));
    }

    private Optional<RegisteredServer> afterLoginServer() {
        if (!settings.afterLoginServer().isEmpty()) {
            return proxy.getServer(settings.afterLoginServer());
        }
        return proxy.getConfiguration().getAttemptConnectionOrder().stream()
                .filter(name -> !isAuthServer(name))
                .map(proxy::getServer).flatMap(Optional::stream).findFirst();
    }

    /** Oyuncunun bulunduğu sunucuya imzalı güncel durumu gönderir. */
    private void pushState(Player player) {
        if (!ready()) {
            return;
        }
        Optional<ServerConnection> current = player.getCurrentServer();
        Optional<ProxyAuthStates.Entry> entry = states.get(player.getUniqueId());
        if (current.isEmpty() || entry.isEmpty()) {
            return;
        }
        ProxyAuthStates.Entry e = entry.get();
        boolean authed = e.state() == ProxyAuthStates.State.AUTHED;
        StatePayload payload = new StatePayload(
                authed ? StatePayload.AuthState.AUTHED : StatePayload.AuthState.PENDING,
                authed ? System.currentTimeMillis() + settings.authedTtl().toMillis() : 0L,
                e.premium());
        SyncMessage message = SyncMessage.outgoing(player.getUniqueId(), e.connectionId, e.epoch(), e.nextSeq(),
                SyncMessage.Op.STATE, payload.encode());
        String serverName = current.get().getServerInfo().getName();
        current.get().sendPluginMessage(CHANNEL, SyncCodec.encode(message, keys, Direction.PROXY_TO_BACKEND, serverName));
    }

    private void heartbeat() {
        for (Player player : proxy.getAllPlayers()) {
            if (states.isAuthed(player.getUniqueId())) {
                pushState(player);
            }
        }
    }
}

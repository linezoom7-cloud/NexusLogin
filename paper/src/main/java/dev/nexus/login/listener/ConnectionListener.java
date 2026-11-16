package dev.nexus.login.listener;

import dev.nexus.login.account.Account;
import dev.nexus.login.account.AccountRepository;
import dev.nexus.login.auth.AuthAudit;
import dev.nexus.login.auth.AuthService;
import dev.nexus.login.auth.LoginThrottle;
import dev.nexus.login.auth.SessionManager;
import dev.nexus.login.ui.Messages;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;

import java.net.InetAddress;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

public final class ConnectionListener implements Listener {

    private static final long DB_TIMEOUT_SECONDS = 10;
    /** Aynı anda en fazla bu kadar pre-login veritabanı sorgusu (Hikari havuzu küçüktür). */
    private static final java.util.concurrent.Semaphore DB_LOOKUPS = new java.util.concurrent.Semaphore(32);

    private final Supplier<CompletableFuture<Void>> ready;
    private final AccountRepository accounts;
    private final SessionManager sessions;
    private final LoginThrottle throttle;
    private final AuthService auth;
    private final AuthAudit audit;
    private final Messages messages;
    private final Logger logger;
    private final java.util.Set<String> onlineNames = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public ConnectionListener(Supplier<CompletableFuture<Void>> ready, AccountRepository accounts,
                              SessionManager sessions, LoginThrottle throttle, AuthService auth,
                              Messages messages, AuthAudit audit, Logger logger) {
        this.ready = ready;
        this.accounts = accounts;
        this.sessions = sessions;
        this.throttle = throttle;
        this.auth = auth;
        this.messages = messages;
        this.audit = audit;
        this.logger = logger;
        try {
            for (Player player : Bukkit.getOnlinePlayers()) {
                onlineNames.add(player.getName().toLowerCase(java.util.Locale.ROOT));
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * Ana thread periyodik döngüsünden çağrılır: Oyundan anormal ayrılan veya pre-login'de
     * takılı kalmış hayalet isimleri temizler.
     */
    public void pruneStaleOnlineNames() {
        java.util.Set<String> active = new java.util.HashSet<>();
        try {
            for (Player player : Bukkit.getOnlinePlayers()) {
                active.add(player.getName().toLowerCase(java.util.Locale.ROOT));
            }
            onlineNames.retainAll(active);
        } catch (Exception ignored) {
        }
    }

    /**
     * Paper bu eventi kendi asenkron thread'inde çalıştırır; burada bloklamak (get) ana
     * thread'i ETKİLEMEZ. Hesap burada önceden yüklenir, join anında DB beklenmez.
     */
    // HIGHEST: MONITOR hariç sonradan çalışan eklentiler reddi allow() ile geri açamaz (ancak aynı öncelikte sıralama garanti değildir).
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        String name = event.getName();
        InetAddress address = event.getAddress();
        String ip = address == null ? "unknown" : address.getHostAddress();

        try {
            ready.get().get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            disallow(event, "kick.not-ready");
            return;
        }

        // Kalıcı sayaç: hesap (her zaman) + IP (yapılandırıldıysa). Yeniden bağlanmak
        // veya sunucuyu yeniden başlatmak bu sayacı sıfırlamaz.
        // Ortak veritabanlı ağda başka auth sunucusundaki hatalar da sayılır; okunamazsa giriş yok.
        if (!throttle.refresh(ip, event.getUniqueId(), java.time.Duration.ofSeconds(5))) {
            disallow(event, "kick.not-ready");
            return;
        }
        Optional<LoginThrottle.Verdict> lock = throttle.check(ip, event.getUniqueId());
        if (lock.isPresent()) {
            long minutes = lock.get().minutes();
            audit.preLoginDenied(name, event.getUniqueId(), ip, "locked");
            disallow(event, "kick.locked", Placeholder.unparsed("minutes", String.valueOf(minutes)));
            return;
        }

        // Offline-mode'da aynı isimle bağlanan, oyundaki asıl oyuncuyu sunucudan attırır.
        // Bunu engellemek için yeni bağlantı reddedilir, oyundaki oyuncu korunur.
        // (Online-mode'da Mojang kimliği doğruladığı için bu gerekmez; orada vanilla davranışı korunur.)
        if (!Bukkit.getOnlineMode() && onlineNames.contains(name.toLowerCase(java.util.Locale.ROOT))) {
            Player existing = Bukkit.getPlayerExact(name);
            if (existing == null || !existing.isOnline()) {
                // Oyuncu aslında oyunda değil (hayalet bağlantı veya zaman aşımı artığı): kaydı temizle
                onlineNames.remove(name.toLowerCase(java.util.Locale.ROOT));
            } else {
                audit.preLoginDenied(name, event.getUniqueId(), ip, "already-online");
                disallow(event, "kick.already-online");
                return;
            }
        }

        // Bağlantı seli veritabanı havuzunu tüketmesin: sınırı aşan bağlantı bekletilmeden reddedilir.
        boolean acquired = false;
        try {
            acquired = DB_LOOKUPS.tryAcquire(2, TimeUnit.SECONDS);
            if (!acquired) {
                audit.preLoginDenied(name, event.getUniqueId(), ip, "overloaded");
                disallow(event, "kick.database");
                return;
            }
            Optional<Account> byUuid = accounts.findByUuid(event.getUniqueId()).get(DB_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            // Sadece offline-mode'da isim kontrolü yapılır. Online-mode'da isim değiştiren oyuncuların
            // eski isimlerini başkası aldığında UUID tek kaynak olduğu için yanlış engelleme yapılmaz.
            if (!Bukkit.getOnlineMode() && byUuid.isEmpty()) {
                // "Notch" kayıtlıyken "notch" ile gelen, farklı bir offline UUID alır: kimliğe bürünme girişimi.
                Optional<Account> byName = accounts.findByName(name).get(DB_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (byName.isPresent()) {
                    audit.preLoginDenied(name, event.getUniqueId(), ip, "name-taken");
                    disallow(event, "kick.name-taken", Placeholder.unparsed("registered", byName.get().playerName()));
                    return;
                }
            }
            // Kayıtlı isim zaten aynıysa devredilecek başka satır olamaz (unique indeks): sorguyu atla.
            if (Bukkit.getOnlineMode() && !byUuid.map(a -> a.playerName().equalsIgnoreCase(name)).orElse(false)) {
                // Mojang ismi doğruladı: aynı ismi tutan başka UUID'li satır, ismini değiştirmiş
                // eski bir oyuncuya aittir. Serbest bırakılmazsa unique isim indeksi bu oyuncunun
                // kaydını ve son giriş güncellemesini kalıcı olarak başarısız kılardı.
                int released = accounts.releaseName(name, event.getUniqueId())
                        .get(DB_TIMEOUT_SECONDS, TimeUnit.SECONDS);
                if (released > 0) {
                    logger.info(() -> "Online-mode isim devri: '" + name + "' adı eski hesaptan serbest bırakıldı.");
                }
            }
            sessions.preload(event.getUniqueId(), byUuid.orElse(null));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            audit.preLoginDenied(name, event.getUniqueId(), ip, "database");
            disallow(event, "kick.database");
        } catch (ExecutionException | TimeoutException e) {
            logger.log(Level.SEVERE, "Pre-login hesap sorgusu başarısız: " + name, e);
            audit.preLoginDenied(name, event.getUniqueId(), ip, "database");
            disallow(event, "kick.database");
        } finally {
            if (acquired) {
                DB_LOOKUPS.release();
            }
        }
    }

    private void disallow(AsyncPlayerPreLoginEvent event, String key, TagResolver... placeholders) {
        event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                messages.kick(key, placeholders));
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        onlineNames.add(event.getPlayer().getName().toLowerCase(java.util.Locale.ROOT));
        auth.begin(event.getPlayer());
    }

    /** Giriş duyurusu doğrulamadan sonra AuthService'ten yapılır: bot akını sohbeti doldurmasın. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void brandJoin(PlayerJoinEvent event) {
        event.joinMessage(null);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        onlineNames.remove(event.getPlayer().getName().toLowerCase(java.util.Locale.ROOT));
        auth.onQuit(event.getPlayer());
    }

    /** Vanilla "left the game" yerine Nexus temalı duyuru; doğrulanmamış oyuncu için hiç duyuru yok. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void brandQuit(PlayerQuitEvent event) {
        if (sessions.isRestricted(event.getPlayer())) {
            event.quitMessage(null);
            return;
        }
        event.quitMessage(messages.get("chat.quit",
                Placeholder.unparsed("player", event.getPlayer().getName())));
    }

    @EventHandler
    public void onPackStatus(PlayerResourcePackStatusEvent event) {
        auth.onPackStatus(event.getPlayer(), event.getID(), event.getStatus());
    }
}

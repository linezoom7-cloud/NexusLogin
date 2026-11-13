package dev.nexus.login;

import dev.nexus.login.account.AccountRepository;
import dev.nexus.login.account.SqlAccountRepository;
import dev.nexus.login.auth.AuthAudit;
import dev.nexus.login.auth.AuthService;
import dev.nexus.login.auth.LoginThrottle;
import dev.nexus.login.auth.SessionManager;
import dev.nexus.login.auth.SqlLoginAttemptRepository;
import dev.nexus.login.command.AdminCommand;
import dev.nexus.login.command.PlayerCommands;
import dev.nexus.login.concurrent.NexusExecutors;
import dev.nexus.login.config.PluginSettings;
import dev.nexus.login.database.DatabaseManager;
import dev.nexus.login.listener.ConnectionListener;
import dev.nexus.login.listener.MenuListener;
import dev.nexus.login.listener.RestrictionListener;
import dev.nexus.login.pack.PackService;
import dev.nexus.login.security.Argon2idHasher;
import dev.nexus.login.security.PasswordHasher;
import dev.nexus.login.ui.AuthEffects;
import dev.nexus.login.ui.Messages;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;

import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Composition root: tüm bağımlılıklar yalnızca burada oluşturulur ve birbirine bağlanır.
 * Diğer sınıflar statik singleton'lara değil, kurucularına verilen bağımlılıklara erişir.
 */
public final class NexusLoginPlugin extends JavaPlugin {

    private NexusExecutors executors;
    private DatabaseManager database;
    private PackService pack;
    private AuthService auth;
    private SqlLoginAttemptRepository attempts;
    private AuthAudit audit;
    private Messages messages;
    private LoginThrottle throttle;
    private RestrictionListener restrictionListener;

    /**
     * Eklentinin açılışta kullandığı yapılandırma. Yeniden başlatma gerektiren bölümler
     * (veritabanı, hash, paket, denetim) hep bu anlık görüntüyle karşılaştırılır; böylece
     * art arda yapılan reload'larda "değişiklik yok" yanılgısı oluşmaz.
     */
    private volatile PluginSettings startupSettings;

    /**
     * Veritabanı + kriptografi + kaynak paketi hazır olduğunda tamamlanır.
     * AsyncPlayerPreLoginEvent bunu bekler; açılış bitmeden kimse içeri giremez.
     */
    private volatile CompletableFuture<Void> ready =
            CompletableFuture.failedFuture(new IllegalStateException("başlatılmadı"));

    @Override
    public void onEnable() {
        saveDefaultConfig();

        PluginSettings settings;
        try {
            settings = PluginSettings.load(getConfig());
        } catch (IllegalArgumentException e) {
            getLogger().severe("config.yml geçersiz: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        this.startupSettings = settings;

        Messages messages = new Messages(this, settings.branding());
        this.messages = messages;
        executors = new NexusExecutors(this, settings.hashing().maxConcurrent());
        database = new DatabaseManager(settings.database(), getDataFolder().toPath(), executors.io(), getLogger());
        PasswordHasher hasher = new Argon2idHasher(settings.hashing(), executors.crypto());
        AccountRepository accounts = new SqlAccountRepository(database, executors.io());
        pack = new PackService(settings.pack(), messages, getLogger(), settings.branding());
        pack.setExportDirectory(getDataFolder().toPath().resolve("pack"));

        SessionManager sessions = new SessionManager();
        attempts = new SqlLoginAttemptRepository(database, executors.io(), getLogger());
        throttle = new LoginThrottle(settings.auth().throttle(), Clock.systemUTC(), attempts);
        audit = new AuthAudit(getDataFolder().toPath(), getLogger(), settings.audit());
        AuthEffects effects = new AuthEffects(this, messages);
        auth = new AuthService(this, accounts, hasher, executors, sessions, throttle, audit, effects, messages,
                pack, settings.auth());

        // Dinleyiciler HEMEN kaydedilir: hazırlık sürerken bile kısıtlamalar devrede olmalı.
        PluginManager pm = getServer().getPluginManager();
        RestrictionListener restrictionListener =
                new RestrictionListener(sessions, messages, settings.auth().allowedCommands());
        this.restrictionListener = restrictionListener;
        pm.registerEvents(restrictionListener, this);
        pm.registerEvents(new MenuListener(auth), this);
        pm.registerEvents(new ConnectionListener(() -> ready, accounts, sessions, throttle, auth, messages,
                audit, getLogger()), this);

        PlayerCommands playerCommands = new PlayerCommands(auth, sessions, messages);
        command("giris").setExecutor(playerCommands);
        command("pin").setExecutor(playerCommands);
        AdminCommand admin = new AdminCommand(accounts, auth, sessions, executors, messages, audit);
        admin.setReloadHandler(this::reloadConfiguration);
        command("nexus").setExecutor(admin);
        command("nexus").setTabCompleter(admin);

        auth.startTicker();
        if (settings.auth().premiumAutoLogin() && !getServer().getOnlineMode()) {
            getLogger().warning("premium-auto-login açık ama sunucu offline-mode: güvenlik nedeniyle premium "
                    + "otomatik giriş DEVRE DIŞI. (İsimle premium tespiti, isim taklidine açıktır.)");
        }

        // Hiçbir adım ana thread'i bloklamaz: DB + şema ve paket üretimi G/Ç thread'inde,
        // Argon2 ısınması crypto thread'inde; sonuç ana thread'e döner.
        if (settings.pack().enabled()) {
            // Kanal kancası ana thread'de kurulmalı (Paper'ın dinleyici listesi thread-safe değil).
            pack.prepare();
        }
        CompletableFuture<Void> packReady = settings.pack().enabled()
                ? CompletableFuture.runAsync(() -> startPack(settings), executors.io())
                : CompletableFuture.completedFuture(null);

        ready = database.start()
                // Kalıcı sayaçlar yüklenmeden hiçbir giriş kabul edilmez.
                .thenCompose(v -> attempts.loadAll())
                .thenAccept(throttle::load)
                .thenRun(() -> attempts.purgeOlderThan(
                        System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30)))
                .thenCompose(v -> hasher.benchmark())
                .thenAccept(duration -> getLogger().info(() -> "Argon2id hazır — tek hash "
                        + duration.toMillis() + " ms (hedef: 200-500 ms)."))
                .thenCombine(packReady, (a, b) -> (Void) null)
                .whenCompleteAsync((v, error) -> {
                    if (error != null) {
                        getLogger().log(Level.SEVERE, "Nexus Login başlatılamadı, eklenti devre dışı bırakılıyor.", error);
                        getServer().getPluginManager().disablePlugin(this);
                        return;
                    }
                    // Kapanma sırasında tamamlanan hazırlık oyunculara dokunmasın.
                    if (!isEnabled()) {
                        return;
                    }
                    // /reload veya PlugMan sonrası zaten oyunda olanlar da giriş akışından geçer.
                    for (Player online : getServer().getOnlinePlayers()) {
                        auth.begin(online);
                    }
                    if (settings.pack().enabled()) {
                        // Sunucu soketi kesin bağlandıktan sonra paket sunumunu yerelden doğrula.
                        getServer().getScheduler().runTaskLaterAsynchronously(this, pack::verifyDelivery, 100L);
                    }
                    getLogger().info("Nexus Login aktif. Portal açık ✦");
                }, executors.mainThread());
    }

    /**
     * {@code /nexus reload}: config'i yeniden okur ve doğrular.
     *
     * <p>Canlı uygulanabilenler (giriş kuralları, ceza eşikleri, komut beyaz listesi, dil/mesajlar)
     * hemen devreye girer. Veritabanı, hash maliyeti, kaynak paketi ve denetim günlüğü
     * döndürme ayarları yalnızca açılışta okunabildiği için restart gerektirir; sonuç
     * kullanıcıya açıkça bildirilir.
     */
    private AdminCommand.ReloadOutcome reloadConfiguration() {
        if (!isEnabled()) {
            return AdminCommand.ReloadOutcome.failed("eklenti etkin değil");
        }
        reloadConfig();

        PluginSettings updated;
        try {
            updated = PluginSettings.load(getConfig());
        } catch (IllegalArgumentException e) {
            return AdminCommand.ReloadOutcome.failed(e.getMessage());
        }

        PluginSettings previous = startupSettings;
        boolean restartRequired = previous == null
                || !previous.database().equals(updated.database())
                || !previous.hashing().equals(updated.hashing())
                || !previous.pack().equals(updated.pack())
                || !previous.audit().equals(updated.audit());

        try {
            auth.applySettings(updated.auth());
            throttle.updateSettings(updated.auth().throttle());
            restrictionListener.updateAllowedCommands(updated.auth().allowedCommands());
            // Dil değişmiş olabilir; reloadConfig() yukarıda çağrıldı.
            messages.reload();
        } catch (RuntimeException e) {
            getLogger().log(Level.WARNING, "Ayarlar canlı uygulanırken hata oluştu.", e);
            return AdminCommand.ReloadOutcome.failed(String.valueOf(e.getMessage()));
        }

        getLogger().info(() -> "Yapılandırma yeniden yüklendi"
                + (restartRequired ? " (bazı değişiklikler için yeniden başlatma gerekli)." : "."));
        return AdminCommand.ReloadOutcome.ok(restartRequired);
    }

    /** Paket hatası eklentiyi durdurmaz: menü cam panelli yedek tasarımla çalışmaya devam eder. */
    private void startPack(PluginSettings settings) {
        try {
            pack.start(settings.auth().pinMaxLength(), executors.io());
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "Kaynak paketi sunulamadı (delivery="
                    + settings.pack().delivery().id() + ", port=" + settings.pack().port()
                    + "). Menü paketsiz görünümle çalışacak.", e);
        }
    }

    private PluginCommand command(String name) {
        return Objects.requireNonNull(getCommand(name), () -> "plugin.yml içinde komut yok: " + name);
    }

    @Override
    public void onDisable() {
        // Sıra önemli: oyuncuların efektleri temizlensin → HTTP kapansın →
        // bekleyen DB yazımları bitsin → havuz kapansın.
        if (auth != null) {
            auth.shutdown();
        }
        if (pack != null) {
            pack.close();
        }
        if (audit != null) {
            audit.close();
        }
        // Bekleyen sayaç yazımları veritabanı havuzu kapanmadan önce bitsin.
        if (attempts != null) {
            attempts.close();
        }
        if (executors != null) {
            executors.close();
        }
        if (database != null) {
            database.close();
        }
    }
}

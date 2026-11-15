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
import dev.nexus.login.listener.EmergencyLockdownListener;
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
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;

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
    private AuthEffects effects;
    private LoginThrottle throttle;
    private RestrictionListener restrictionListener;
    private volatile @Nullable EmergencyLockdownListener lockdownListener;
    private volatile @Nullable AdminCommand adminCommand;
    private volatile @Nullable ConnectionListener connectionListener;

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

        // Konsol veya yöneticilerin lockdown anında bile /nexus reload komutunu çalıştırabilmesi
        // için admin komutu en başta bağlanır.
        AdminCommand admin = new AdminCommand(null, null, null, null, null, null);
        admin.setReloadHandler(this::reloadConfiguration);
        this.adminCommand = admin;
        command("nexus").setExecutor(admin);
        command("nexus").setTabCompleter(admin);

        PluginSettings settings;
        try {
            settings = PluginSettings.load(getConfig());
        } catch (IllegalArgumentException e) {
            getLogger().severe("config.yml geçersiz: " + e.getMessage());
            enterLockdown("config.yml geçersiz: " + e.getMessage());
            return;
        }

        startPlugin(settings);
    }

    private void startPlugin(PluginSettings settings) {
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
        effects = new AuthEffects(this, messages);
        auth = new AuthService(this, accounts, hasher, executors, sessions, throttle, audit, effects, messages,
                pack, settings.auth());

        // Dinleyiciler HEMEN kaydedilir: hazırlık sürerken bile kısıtlamalar devrede olmalı.
        PluginManager pm = getServer().getPluginManager();
        RestrictionListener restrictionListener =
                new RestrictionListener(sessions, messages, settings.auth().allowedCommands());
        this.restrictionListener = restrictionListener;
        pm.registerEvents(restrictionListener, this);
        pm.registerEvents(new MenuListener(auth), this);
        ConnectionListener connectionListener = new ConnectionListener(() -> ready, accounts, sessions, throttle,
                auth, messages, audit, getLogger());
        this.connectionListener = connectionListener;
        pm.registerEvents(connectionListener, this);
        auth.registerCleanupTask(connectionListener::pruneStaleOnlineNames);

        PlayerCommands playerCommands = new PlayerCommands(auth, sessions, messages);
        command("giris").setExecutor(playerCommands);
        command("pin").setExecutor(playerCommands);

        if (adminCommand != null) {
            adminCommand.bind(accounts, auth, sessions, executors, messages, audit);
        }

        auth.startTicker();
        logSecurityWarnings(settings);

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
                        getLogger().log(Level.SEVERE, "Nexus Login başlatılamadı, güvenlik kilidi (fail-closed) devreye giriyor.", error);
                        enterLockdown("Açılış hatası: " + error.getMessage());
                        return;
                    }
                    // Kapanma sırasında tamamlanan hazırlık oyunculara dokunmasın.
                    if (!isEnabled()) {
                        return;
                    }
                    // Eğer güvenlik kilidi aktif idiyse artık kaldırılabilir
                    if (lockdownListener != null) {
                        HandlerList.unregisterAll(lockdownListener);
                        lockdownListener = null;
                        getLogger().info("Güvenlik kilidi (lockdown) kaldırıldı — sunucu yeniden girişlere açıldı.");
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

        // Eğer başlangıçta açılış hatası/lockdown nedeniyle başlatılamadıysa:
        if (startupSettings == null || auth == null) {
            try {
                startPlugin(updated);
                return AdminCommand.ReloadOutcome.ok(false);
            } catch (Exception e) {
                return AdminCommand.ReloadOutcome.failed("Başlatma hatası: " + e.getMessage());
            }
        }

        PluginSettings previous = startupSettings;
        boolean restartRequired = !previous.database().equals(updated.database())
                || !previous.hashing().equals(updated.hashing())
                || !previous.pack().equals(updated.pack())
                || !previous.audit().equals(updated.audit())
                // Menü/mesaj markası canlı uygulanır; kaynak paketinin GÖRSELİ ise açılışta
                // üretildiği için tam etki ancak yeniden başlatmayla alınır.
                || !previous.branding().equals(updated.branding());

        try {
            auth.applySettings(updated.auth());
            throttle.updateSettings(updated.auth().throttle());
            if (restrictionListener != null) {
                restrictionListener.updateAllowedCommands(updated.auth().allowedCommands());
            }
            // Marka önce uygulanır: reload() palette'i okur, ardından efektler tazelenir.
            if (messages != null) {
                messages.applyBranding(updated.branding());
                // Dil değişmiş olabilir; reloadConfig() yukarıda çağrıldı.
                messages.reload();
            }
            if (effects != null) {
                effects.reloadBranding();
            }
            // Başarılı reload sonrası güvenlik kilidini kaldır
            if (lockdownListener != null && ready.isDone() && !ready.isCompletedExceptionally()) {
                HandlerList.unregisterAll(lockdownListener);
                lockdownListener = null;
                getLogger().info("Güvenlik kilidi (lockdown) kaldırıldı — sunucu yeniden girişlere açıldı.");
            }
        } catch (RuntimeException e) {
            getLogger().log(Level.WARNING, "Ayarlar canlı uygulanırken hata oluştu.", e);
            return AdminCommand.ReloadOutcome.failed(String.valueOf(e.getMessage()));
        }

        getLogger().info(() -> "Yapılandırma yeniden yüklendi"
                + (restartRequired ? " (bazı değişiklikler için yeniden başlatma gerekli)." : "."));
        return AdminCommand.ReloadOutcome.ok(restartRequired);
    }

    /**
     * Yapılandırmanın güvenlik açısından riskli kombinasyonlarını açılışta konsola yazar.
     * Sunucu sahibi "güvenli varsayılan" beklentisiyle yanlış kurulum yapmamalıdır.
     */
    private void logSecurityWarnings(PluginSettings settings) {
        if (!settings.auth().rememberFor().isZero()) {
            getLogger().warning("session-remember-minutes > 0: \"beni hatırla\" aynı IP + UUID ile PIN'i "
                    + "atlar. Proxy/relay veya offline-mode arkasında bu, hesap devralmaya yol açabilir; "
                    + "mümkünse 0 tutun.");
        }
        if (!getServer().getOnlineMode()) {
            getLogger().warning("Sunucu offline-mode: UUID oyuncu adından türetilir ve istemci IP'si "
                    + "sahtelenebilir. BungeeCord legacy forwarding kullanıyorsanız backend portunu dışarıya "
                    + "KAPATIN ve Velocity modern forwarding / PROXY protocol kullanın.");
        }
        if (settings.auth().premiumAutoLogin()) {
            if (!getServer().getOnlineMode()) {
                getLogger().warning("premium-auto-login açık ama sunucu offline-mode: güvenlik nedeniyle premium "
                        + "otomatik giriş DEVRE DIŞI. (İsimle premium tespiti, isim taklidine açıktır.)");
            } else {
                getLogger().warning("premium-auto-login açık: çalınmış bir Mojang/Microsoft oturumu PIN "
                        + "sorulmadan giriş yapar (ikinci faktör yok).");
            }
        }
        if (settings.hashing().pepper().isEmpty()) {
            getLogger().warning("security.argon2.pepper boş: veritabanı sızarsa PIN hash'leri pepper "
                    + "olmadan çevrimdışı kırılabilir. En az 16 karakterlik rastgele bir değer ayarlayın.");
        }
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

    /**
     * Fail-closed güvenlik kilidi: Kritik açılış hatasında sunucuyu ve eklentiyi savunmasız
     * (şifresiz giriş yapılabilir) bırakmak yerine tüm bağlantıları engelleyen kilide geçer.
     */
    private void enterLockdown(String reason) {
        getLogger().severe("GÜVENLİK KİLİDİ DEVREDE (FAIL-CLOSED): " + reason);
        if (lockdownListener != null) {
            HandlerList.unregisterAll(lockdownListener);
        }
        EmergencyLockdownListener listener = new EmergencyLockdownListener(reason);
        this.lockdownListener = listener;
        getServer().getPluginManager().registerEvents(listener, this);
        for (Player online : getServer().getOnlinePlayers()) {
            online.kick(net.kyori.adventure.text.Component.text("§c[NexusLogin] Güvenlik Kilidi: Kimlik doğrulama sistemi başlatılamadı."));
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

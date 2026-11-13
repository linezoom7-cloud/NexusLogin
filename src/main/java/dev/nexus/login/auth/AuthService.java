package dev.nexus.login.auth;

import dev.nexus.login.account.Account;
import dev.nexus.login.account.AccountRepository;
import dev.nexus.login.concurrent.NexusExecutors;
import dev.nexus.login.config.AuthSettings;
import dev.nexus.login.menu.PadButton;
import dev.nexus.login.menu.PinLayout;
import dev.nexus.login.menu.PinMenu;
import dev.nexus.login.menu.PinMenuMode;
import dev.nexus.login.pack.PackService;
import dev.nexus.login.security.PasswordHasher;
import dev.nexus.login.security.VerificationResult;
import dev.nexus.login.ui.AuthEffects;
import dev.nexus.login.ui.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.Nullable;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Giriş akışının beyni: join → (premium | hatırlanan | PIN menüsü) → oyuna dahil.
 *
 * <p>Tüm public metotlar ana thread'den çağrılır. Asenkron işler (Argon2, JDBC) bittiğinde
 * sonuçları {@code executors.mainThread()} ile buraya döner ve oturum hâlâ geçerli mi diye
 * kontrol edilir ({@link #isCurrent}); oyuncu bu arada çıkmış olabilir.
 */
public final class AuthService {

    private static final long MENU_DELAY_TICKS = 20L;
    private static final long PACK_WAIT_TICKS = 20L * 30;
    private static final long REOPEN_DELAY_TICKS = 10L;

    private final Plugin plugin;
    private final Logger logger;
    private final AccountRepository accounts;
    private final PasswordHasher hasher;
    private final NexusExecutors executors;
    private final SessionManager sessions;
    private final LoginThrottle throttle;
    private final AuthAudit audit;
    private final AuthEffects effects;
    private final Messages messages;
    private final PackService pack;
    private volatile AuthSettings settings;
    private volatile PinPolicy pinPolicy;
    private volatile boolean premiumActive;

    private @Nullable BukkitTask ticker;
    private int frame;

    public AuthService(Plugin plugin, AccountRepository accounts, PasswordHasher hasher, NexusExecutors executors,
                       SessionManager sessions, LoginThrottle throttle, AuthAudit audit, AuthEffects effects,
                       Messages messages, PackService pack, AuthSettings settings) {
        this.plugin = plugin;
        this.logger = plugin.getLogger();
        this.accounts = accounts;
        this.hasher = hasher;
        this.executors = executors;
        this.sessions = sessions;
        this.throttle = throttle;
        this.audit = audit;
        this.effects = effects;
        this.messages = messages;
        this.pack = pack;
        this.settings = settings;
        this.pinPolicy = new PinPolicy(settings.pinMinLength(), settings.pinMaxLength());
        this.premiumActive = settings.premiumAutoLogin() && Bukkit.getOnlineMode();
    }

    public boolean premiumActive() {
        return premiumActive;
    }

    /**
     * {@code /nexus reload} sonrası giriş kurallarını canlı uygular. Var olan oturumların
     * başlangıç zamanı değişmez (timeout geriye dönük uygulanmaz); yeni menüler ve yeni
     * girişler yeni PIN sınırlarını kullanır.
     */
    public void applySettings(AuthSettings newSettings) {
        this.settings = newSettings;
        this.pinPolicy = new PinPolicy(newSettings.pinMinLength(), newSettings.pinMaxLength());
        this.premiumActive = newSettings.premiumAutoLogin() && Bukkit.getOnlineMode();
    }

    // ═════════════════════════ yaşam döngüsü ═════════════════════════

    public void startTicker() {
        ticker = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 2L, 2L);
    }

    private void tick() {
        frame++;
        Instant now = Instant.now();
        for (AuthSession session : sessions.all()) {
            if (!session.isRestricted()) {
                continue;
            }
            Player player = Bukkit.getPlayer(session.uuid());
            if (player == null) {
                continue;
            }
            if (session.isExpired(now)) {
                kick(player, "kick.timeout");
                continue;
            }
            if (session.bossBar() != null) {
                effects.animate(player, session.bossBar(), frame,
                        session.remainingSeconds(now), session.remainingFraction(now));
            }
        }
        if (frame % 300 == 0) {
            sessions.purgeStalePreloads();
            throttle.purgeStale();
        }
    }

    /** Eklenti kapanırken: kimse kör/yavaş kalmasın, envanter ve konumlar iade edilsin. */
    public void shutdown() {
        if (ticker != null) {
            ticker.cancel();
        }
        for (AuthSession session : sessions.all()) {
            Player player = Bukkit.getPlayer(session.uuid());
            if (player != null) {
                detachUi(player, session);
                effects.clearRestrictions(player, session.restrictionSnapshot());
                restorePlayerState(player, session);
            }
        }
    }

    // ═════════════════════════ giriş / çıkış ═════════════════════════

    /** PlayerJoinEvent veya eklenti açılırken zaten oyunda olan oyuncular için. */
    public void begin(Player player) {
        // Idempotent: /reload sonrası açılış döngüsü ile PlayerJoinEvent aynı oyuncu için
        // art arda çalışabilir. İkinci kez begin() envanteri ikinci kez saklayıp silerdi.
        if (sessions.get(player.getUniqueId()) != null) {
            return;
        }
        String ip = ipOf(player);
        AuthSession session = sessions.create(player.getUniqueId(), ip, settings.timeout());
        // Hesap bilgisi gelmeden önce bile kısıtla: hiçbir tick boyunca açık kapı bırakma.
        session.restrictionSnapshot(effects.applyRestrictions(player));
        session.originalLocation(player.getLocation());
        if (settings.hideInventory()) {
            session.storedInventory(player.getInventory().getContents().clone());
            player.getInventory().clear();
        }
        if (settings.teleportToSpawn()) {
            player.teleport(player.getWorld().getSpawnLocation());
        }
        player.updateCommands();

        Optional<SessionManager.Preloaded> preloaded = sessions.takePreloaded(player.getUniqueId());
        if (preloaded.isPresent()) {
            onAccountLoaded(player, session, preloaded.get().account());
            return;
        }
        accounts.findByUuid(player.getUniqueId()).whenCompleteAsync((account, error) -> {
            if (!isCurrent(player, session)) {
                return;
            }
            if (error != null) {
                logger.log(Level.SEVERE, "Hesap yüklenemedi: " + player.getName(), error);
                kick(player, "kick.database");
                return;
            }
            onAccountLoaded(player, session, account.orElse(null));
        }, executors.mainThread());
    }

    private void onAccountLoaded(Player player, AuthSession session, @Nullable Account account) {
        session.account(account);

        // /nexus forcelogin hesap sorgusundan önce tamamlanmış olabilir. Bu durumda akışı
        // yeniden başlatmak oyuncuyu tekrar kısıtlar ve envanterini ikinci kez saklardı.
        if (session.state() == AuthState.AUTHENTICATED) {
            return;
        }

        if (premiumActive) {
            premiumLogin(player, session);
            return;
        }
        if (account != null && account.hasPassword() && sessions.consumeRemembered(session.uuid(), session.ip())) {
            completeLogin(player, session, LoginKind.REMEMBERED);
            return;
        }

        session.state(AuthState.UNAUTHENTICATED);
        effects.intro(player, account != null && account.hasPassword());
        var bar = effects.createBossBar();
        session.bossBar(bar);
        player.showBossBar(bar);

        if (pack.send(player)) {
            session.waitingForPack(true);
            effects.actionBar(player, "actionbar.pack-loading");
            // Oyuncu istemi yanıtlamazsa sonsuza dek beklemeyelim.
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (isCurrent(player, session) && session.waitingForPack()) {
                    session.waitingForPack(false);
                    openGateMenu(player, session);
                }
            }, PACK_WAIT_TICKS);
        } else {
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (isCurrent(player, session)) {
                    openGateMenu(player, session);
                }
            }, MENU_DELAY_TICKS);
        }
    }

    private void premiumLogin(Player player, AuthSession session) {
        Account account = session.account();
        if (account == null) {
            accounts.create(session.uuid(), player.getName(), null, true, session.ip())
                    .whenCompleteAsync((created, error) -> {
                        if (error != null) {
                            logger.log(Level.SEVERE, "Premium hesap oluşturulamadı: " + player.getName(), error);
                        } else if (isCurrent(player, session)) {
                            session.account(created);
                        }
                    }, executors.mainThread());
        } else if (!account.premium()) {
            session.account(account.withPremium(true));
            logFailure(accounts.setPremium(session.uuid(), true), "premium işareti");
        }
        completeLogin(player, session, LoginKind.PREMIUM);
    }

    public void onPackStatus(Player player, UUID packId, PlayerResourcePackStatusEvent.Status status) {
        AuthSession session = sessions.get(player.getUniqueId());
        if (session == null || !pack.isOurPack(packId)) {
            return;
        }
        logPackStatus(player, status);
        if (status == PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED) {
            session.packLoaded(true);
        }
        boolean finalStatus = switch (status) {
            case ACCEPTED, DOWNLOADED -> false;
            default -> true;
        };
        if (finalStatus && session.waitingForPack()) {
            session.waitingForPack(false);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (isCurrent(player, session)) {
                    openGateMenu(player, session);
                }
            }, 5L);
        } else if (status == PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED) {
            // 30 sn dolunca düz menü açılmışsa başlık glifsiz kalır. Paket düşünce yeniden çiz.
            refreshStyledMenu(player, session);
        }
    }

    /**
     * İstemcinin paket durumunu loglar. Sessiz başarısızlık en sık görülen destek sorusuydu:
     * paket indirilemediğinde oyuncu yedek menüyü görüyor ama sunucu tarafında hiçbir iz kalmıyordu.
     */
    private void logPackStatus(Player player, PlayerResourcePackStatusEvent.Status status) {
        switch (status) {
            case DECLINED -> logger.info(() -> "Kaynak paketi reddedildi: " + player.getName()
                    + " — menü yedek tasarımla açılacak.");
            case DISCARDED -> logger.info(() -> "Kaynak paketi istemci tarafından kaldırıldı: " + player.getName());
            case FAILED_DOWNLOAD -> {
                String url = pack.lastUrl(player.getUniqueId());
                logger.warning("Kaynak paketi indirilemedi: " + player.getName()
                        + " — istemcinin indirmeye çalıştığı adres: " + (url == null ? "?" : url)
                        + " (delivery=" + pack.deliveryId() + "). Bu adres tarayıcıdan da açılmıyorsa "
                        + "barındırma panelinin aktarıcısı HTTP'yi geçirmiyor olabilir; "
                        + "resource-pack.delivery: url deneyin. Menü yedek tasarımla açılacak.");
                effects.actionBar(player, "actionbar.pack-failed");
            }
            case INVALID_URL -> {
                logger.warning("Kaynak paketi adresi geçersiz: " + player.getName()
                        + " — resource-pack.public-url / public-host ayarlarını kontrol et.");
                effects.actionBar(player, "actionbar.pack-failed");
            }
            case FAILED_RELOAD -> logger.warning("Kaynak paketi yeniden yüklenemedi: " + player.getName());
            case SUCCESSFULLY_LOADED -> logger.info(() -> "Kaynak paketi yüklendi: " + player.getName());
            case ACCEPTED, DOWNLOADED ->
                    logger.fine(() -> "Kaynak paketi durumu: " + player.getName() + " → " + status);
        }
    }

    public void onQuit(Player player) {
        pack.forget(player.getUniqueId());
        AuthSession session = sessions.remove(player.getUniqueId());
        if (session == null) {
            return;
        }
        detachUi(player, session);
        // Oyuncu verisi çıkışta diske yazılır: sonsuz körlük efekti kalıcı olmasın, eşyalar ve konum korunsun.
        effects.clearRestrictions(player, session.restrictionSnapshot());
        restorePlayerState(player, session);
        if (session.state() == AuthState.AUTHENTICATED && session.account() != null) {
            sessions.remember(session.uuid(), session.ip(), settings.rememberFor());
        }
    }

    // ═════════════════════════ menü ═════════════════════════

    private void openGateMenu(Player player, AuthSession session) {
        if (session.state() != AuthState.UNAUTHENTICATED) {
            return;
        }
        Account account = session.account();
        PinMenuMode mode = account != null && account.hasPassword() ? PinMenuMode.LOGIN : PinMenuMode.REGISTER;
        openMenu(player, session, mode);
    }

    /**
     * Sandık başlığı oluşturulduktan sonra değişmez. Paket, camsız menü açıldıktan
     * sonra yüklenirse aynı mod ve girilen PIN ile glifli başlık yeniden açılır.
     */
    private void refreshStyledMenu(Player player, AuthSession session) {
        PinMenu current = session.menu();
        if (current == null || current.styled() || !session.packLoaded() || !isCurrent(player, session)) {
            return;
        }
        PinMenu.Carry carry = current.carry();
        openMenu(player, session, carry.mode());
        PinMenu fresh = session.menu();
        if (fresh != null) {
            fresh.restore(carry);
        }
    }

    private void openMenu(Player player, AuthSession session, PinMenuMode mode) {
        PinMenu old = session.menu();
        if (old != null) {
            old.wipe();
        }
        PinMenu menu = new PinMenu(messages, mode, session.packLoaded(),
                settings.pinMinLength(), settings.pinMaxLength());
        session.menu(menu);
        player.openInventory(menu.getInventory());
        effects.menuOpened(player);
    }

    /** /giris komutu ve menü kapatıldığında. */
    public void reopenGate(Player player) {
        AuthSession session = sessions.get(player.getUniqueId());
        if (session == null || session.state() != AuthState.UNAUTHENTICATED || session.waitingForPack()) {
            return;
        }
        PinMenu menu = session.menu();
        if (menu == null) {
            openGateMenu(player, session);
        } else if (!(player.getOpenInventory().getTopInventory().getHolder(false) instanceof PinMenu)) {
            player.openInventory(menu.getInventory());
            effects.menuOpened(player);
        }
    }

    /** /pin komutu: giriş yapmış oyuncu PIN'ini değiştirir. */
    public boolean openChangeMenu(Player player) {
        AuthSession session = sessions.get(player.getUniqueId());
        if (session == null || session.isRestricted() || session.account() == null) {
            return false;
        }
        openMenu(player, session, session.account().hasPassword() ? PinMenuMode.CHANGE_CURRENT : PinMenuMode.CHANGE_NEW);
        return true;
    }

    public void onMenuClosed(Player player, PinMenu menu, InventoryCloseEvent.Reason reason) {
        AuthSession session = sessions.get(player.getUniqueId());
        if (session == null || session.menu() != menu) {
            return;
        }
        if (!menu.mode().isGate()) {
            // İsteğe bağlı PIN değiştirme menüsü: kapatınca iptal.
            menu.wipe();
            session.menu(null);
            return;
        }
        switch (reason) {
            case OPEN_NEW, DISCONNECT, PLUGIN -> {
            }
            default -> {
                effects.actionBar(player, "actionbar.menu-closed");
                Bukkit.getScheduler().runTaskLater(plugin, () -> {
                    if (isCurrent(player, session)) {
                        reopenGate(player);
                    }
                }, REOPEN_DELAY_TICKS);
            }
        }
    }

    public void onMenuClick(Player player, PinMenu menu, int slot, boolean shift) {
        AuthSession session = sessions.get(player.getUniqueId());
        if (session == null || session.menu() != menu || menu.busy()) {
            return;
        }
        Optional<PadButton> button = PinLayout.buttonAt(slot);
        if (button.isEmpty()) {
            return;
        }
        switch (button.get()) {
            case PadButton.Digit(char value) -> {
                if (menu.append(value)) {
                    effects.keyPressed(player);
                }
            }
            case PadButton.Delete() -> {
                if (shift) {
                    menu.clearInput();
                } else {
                    menu.backspace();
                }
                effects.keyPressed(player);
            }
            case PadButton.Confirm() -> {
                if (!menu.meetsMinimum()) {
                    fail(player, menu, messages.get("pin.too-short", minMax(menu.submitMinimum())));
                    return;
                }
                submit(player, session, menu, menu.takeInput());
            }
        }
    }

    // ═════════════════════════ PIN gönderimi ═════════════════════════

    private void submit(Player player, AuthSession session, PinMenu menu, char[] pin) {
        switch (menu.mode()) {
            case LOGIN -> verifyCurrent(player, session, menu, pin, () -> completeLogin(player, session, LoginKind.PIN));
            case CHANGE_CURRENT -> verifyCurrent(player, session, menu, pin, () -> menu.mode(PinMenuMode.CHANGE_NEW));
            case REGISTER -> acceptFirstEntry(player, menu, pin, PinMenuMode.REGISTER_CONFIRM);
            case CHANGE_NEW -> acceptFirstEntry(player, menu, pin, PinMenuMode.CHANGE_CONFIRM);
            case REGISTER_CONFIRM -> {
                if (confirmMatches(player, menu, pin, PinMenuMode.REGISTER)) {
                    register(player, session, menu, pin);
                }
            }
            case CHANGE_CONFIRM -> {
                if (confirmMatches(player, menu, pin, PinMenuMode.CHANGE_NEW)) {
                    changePin(player, session, menu, pin);
                }
            }
        }
    }

    private void verifyCurrent(Player player, AuthSession session, PinMenu menu, char[] pin, Runnable onSuccess) {
        Account account = session.account();
        if (account == null || !account.hasPassword()) {
            wipe(pin);
            return;
        }
        // verify() kendi kopyasını siler; ikinci kopya yalnızca gerekirse yeniden hash için tutulur.
        char[] rehashCopy = pin.clone();
        menu.busy(true);
        hasher.verify(account.passwordHash(), pin).whenCompleteAsync((result, error) -> {
            if (!isCurrent(player, session) || session.menu() != menu) {
                wipe(rehashCopy);
                return;
            }
            menu.busy(false);
            if (error != null) {
                wipe(rehashCopy);
                logger.log(Level.SEVERE, "PIN doğrulanamadı: " + player.getName(), error);
                fail(player, menu, messages.get("pin.internal-error"));
                return;
            }
            switch (result) {
                case VerificationResult.Match(boolean needsRehash) -> {
                    if (needsRehash) {
                        upgradeHash(session, rehashCopy);
                    } else {
                        wipe(rehashCopy);
                    }
                    onSuccess.run();
                }
                case VerificationResult.Mismatch() -> {
                    wipe(rehashCopy);
                    wrongPin(player, session, menu);
                }
            }
        }, executors.mainThread());
    }

    private void wrongPin(Player player, AuthSession session, PinMenu menu) {
        LoginThrottle.FailureResult result = throttle.recordFailure(session.ip(), session.uuid());
        audit.loginFailure(player.getName(), session.uuid(), session.ip(),
                result.attempts(), result.threshold());
        if (result.locked()) {
            long minutes = result.lock().minutes();
            audit.lockout(player.getName(), session.uuid(), session.ip(), minutes, result.lock().blockCount());
            kick(player, "kick.locked", Placeholder.unparsed("minutes", String.valueOf(minutes)));
            return;
        }
        fail(player, menu, messages.get("pin.wrong",
                Placeholder.unparsed("remaining", String.valueOf(result.remaining()))));
    }

    private void acceptFirstEntry(Player player, PinMenu menu, char[] pin, PinMenuMode next) {
        Optional<PinPolicy.Violation> violation = pinPolicy.check(pin);
        if (violation.isPresent()) {
            wipe(pin);
            fail(player, menu, messages.get(violation.get().messageKey(), minMax()));
            return;
        }
        menu.firstEntry(pin);
        menu.mode(next);
        effects.menuOpened(player);
    }

    private boolean confirmMatches(Player player, PinMenu menu, char[] pin, PinMenuMode retryMode) {
        char[] first = menu.firstEntry();
        boolean matches = first != null && Arrays.equals(first, pin);
        menu.firstEntry(null);
        if (!matches) {
            wipe(pin);
            menu.mode(retryMode);
            fail(player, menu, messages.get("pin.mismatch"));
        }
        return matches;
    }

    private void register(Player player, AuthSession session, PinMenu menu, char[] pin) {
        menu.busy(true);
        Account existing = session.account();
        CompletableFuture<Integer> ipCount = existing != null || settings.maxAccountsPerIp() == 0
                ? CompletableFuture.completedFuture(0)
                : accounts.countByRegistrationIp(session.ip());

        ipCount.thenCompose(count -> {
                    if (count >= settings.maxAccountsPerIp() && settings.maxAccountsPerIp() > 0) {
                        throw new CompletionException(new IpLimitReached());
                    }
                    return hasher.hash(pin);
                })
                .thenCompose(hash -> existing == null
                        ? accounts.create(session.uuid(), player.getName(), hash, false, session.ip())
                        : accounts.updatePasswordHash(session.uuid(), hash).thenApply(ok -> existing.withPasswordHash(hash)))
                .whenCompleteAsync((account, error) -> {
                    wipe(pin); // hasher zaten sildi; IP limiti gibi erken çıkışlarda da silindiğinden emin ol
                    if (!isCurrent(player, session) || session.menu() != menu) {
                        return;
                    }
                    menu.busy(false);
                    if (error != null) {
                        menu.mode(PinMenuMode.REGISTER);
                        if (rootCause(error) instanceof IpLimitReached) {
                            fail(player, menu, messages.get("pin.ip-limit",
                                    Placeholder.unparsed("max", String.valueOf(settings.maxAccountsPerIp()))));
                        } else {
                            logger.log(Level.SEVERE, "Kayıt başarısız: " + player.getName(), error);
                            fail(player, menu, messages.get("pin.internal-error"));
                        }
                        return;
                    }
                    session.account(account);
                    completeLogin(player, session, LoginKind.REGISTER);
                }, executors.mainThread());
    }

    private void changePin(Player player, AuthSession session, PinMenu menu, char[] pin) {
        Account account = session.account();
        if (account == null) {
            wipe(pin);
            return;
        }
        menu.busy(true);
        hasher.hash(pin)
                .thenCompose(hash -> accounts.updatePasswordHash(session.uuid(), hash).thenApply(ok -> hash))
                .whenCompleteAsync((hash, error) -> {
                    if (!isCurrent(player, session) || session.menu() != menu) {
                        return;
                    }
                    menu.busy(false);
                    if (error != null) {
                        logger.log(Level.SEVERE, "PIN değiştirilemedi: " + player.getName(), error);
                        menu.mode(PinMenuMode.CHANGE_NEW);
                        fail(player, menu, messages.get("pin.internal-error"));
                        return;
                    }
                    session.account(account.withPasswordHash(hash));
                    closeMenu(player, session);
                    player.sendMessage(messages.get("pin.changed"));
                    audit.pinChanged(player.getName(), session.uuid(), session.ip());
                    effects.success(player, "title.changed-sub");
                }, executors.mainThread());
    }

    /** Config'te Argon2 maliyeti artırıldıysa, başarılı girişte hash sessizce güçlendirilir. */
    private void upgradeHash(AuthSession session, char[] pin) {
        CompletableFuture<Boolean> saved = hasher.hash(pin)
                .thenCompose(hash -> accounts.updatePasswordHash(session.uuid(), hash));
        logFailure(saved, "hash yükseltme");
    }

    // ═════════════════════════ sonuç ═════════════════════════

    private void completeLogin(Player player, AuthSession session, LoginKind kind) {
        session.state(AuthState.AUTHENTICATED);
        session.waitingForPack(false);
        detachUi(player, session);
        effects.clearRestrictions(player, session.restrictionSnapshot());
        restorePlayerState(player, session);
        player.updateCommands();

        // Başarı kanıtlandı: yalnızca hesabın hatalı deneme bütçesi sıfırlanır.
        throttle.resetAccount(session.uuid());
        audit.loginSuccess(player.getName(), session.uuid(), session.ip(), kind.name());

        switch (kind) {
            case PREMIUM -> effects.premium(player);
            case REGISTER -> effects.success(player, "title.registered-sub");
            case REMEMBERED -> effects.success(player, "title.remembered-sub");
            case PIN, FORCED -> effects.success(player, "title.success-sub");
        }

        if (session.account() != null && kind != LoginKind.PREMIUM) {
            logFailure(accounts.updateLastLogin(session.uuid(), player.getName(), session.ip()), "son giriş kaydı");
        }
        logger.info(() -> player.getName() + " giriş yaptı (" + kind + ").");
    }

    /** /nexus forcelogin */
    public boolean forceLogin(Player player) {
        AuthSession session = sessions.get(player.getUniqueId());
        if (session == null || !session.isRestricted()) {
            return false;
        }
        completeLogin(player, session, LoginKind.FORCED);
        return true;
    }

    /**
     * Yalnızca hesap anahtarını temizler. Başarılı giriş ve geriye dönük çağrılar
     * için tutulur; yönetici kilidi için {@link #clearThrottle(Account)} kullanılmalıdır
     * (IP cezalarını da temizler).
     */
    public void resetThrottle(UUID uuid) {
        throttle.resetAccount(uuid);
    }

    /**
     * Yönetici müdahalesi: hesabın kalıcı kaba kuvvet cezasını (hesap + hesabın bilinen
     * kayıt/son IP'leri) bellekten ve veritabanından tamamen siler. Hesap silinmeden
     * yanlışlıkla kilitlenmiş bir oyuncuyu açmak için kullanılır.
     */
    public void clearThrottle(Account account) {
        throttle.reset(account.uuid(), Arrays.asList(account.registrationIp(), account.lastIp()));
    }

    /** Yönetici tek bir IP'nin kalıcı kaba kuvvet cezasını elle siler. */
    public void clearIpThrottle(String ip) {
        throttle.resetIp(ip);
    }

    // ═════════════════════════ yardımcılar ═════════════════════════

    private void fail(Player player, PinMenu menu, Component message) {
        menu.notice(message);
        effects.error(player);
    }

    private void closeMenu(Player player, AuthSession session) {
        PinMenu menu = session.menu();
        session.menu(null); // önce ayır: kapanma eventi "yeniden aç" demesin
        if (menu != null) {
            menu.wipe();
            if (player.getOpenInventory().getTopInventory().getHolder(false) == menu) {
                player.closeInventory(InventoryCloseEvent.Reason.PLUGIN);
            }
        }
    }

    private void detachUi(Player player, AuthSession session) {
        closeMenu(player, session);
        if (session.bossBar() != null) {
            player.hideBossBar(session.bossBar());
            session.bossBar(null);
        }
    }

    private void restorePlayerState(Player player, AuthSession session) {
        if (session.storedInventory() != null) {
            player.getInventory().setContents(session.storedInventory());
            session.storedInventory(null);
        }
        if (settings.teleportToSpawn() && settings.restorePreviousLocation() && session.originalLocation() != null) {
            player.teleport(session.originalLocation());
            session.originalLocation(null);
        }
    }

    private void kick(Player player, String key, TagResolver... placeholders) {
        // Oturumu hemen kaldır: oyuncu çıkış eventini göndermeden önce bile kısıtlamalar
        // temizlenir ve aynı bağlantıda ikinci bir kick olayı oturumu bulamaz.
        AuthSession session = sessions.remove(player.getUniqueId());
        if (session != null) {
            detachUi(player, session);
            effects.clearRestrictions(player, session.restrictionSnapshot());
            restorePlayerState(player, session);
        }
        player.kick(messages.kick(key, placeholders));
    }

    private boolean isCurrent(Player player, AuthSession session) {
        return player.isOnline() && sessions.get(player.getUniqueId()) == session;
    }

    private TagResolver minMax() {
        return minMax(settings.pinMinLength());
    }

    private TagResolver minMax(int min) {
        return TagResolver.resolver(
                Placeholder.unparsed("min", String.valueOf(min)),
                Placeholder.unparsed("max", String.valueOf(settings.pinMaxLength())));
    }

    private void logFailure(CompletableFuture<?> future, String what) {
        future.exceptionally(error -> {
            logger.log(Level.WARNING, "Arka plan işlemi başarısız (" + what + ")", error);
            return null;
        });
    }

    private static void wipe(char[] pin) {
        Arrays.fill(pin, '\0');
    }

    private static Throwable rootCause(Throwable error) {
        Throwable t = error;
        while (t instanceof CompletionException && t.getCause() != null) {
            t = t.getCause();
        }
        return t;
    }

    public static String ipOf(Player player) {
        InetSocketAddress address = player.getAddress();
        if (address == null) {
            return "unknown";
        }
        InetAddress resolved = address.getAddress();
        return resolved == null ? address.getHostString() : resolved.getHostAddress();
    }

    private static final class IpLimitReached extends RuntimeException {
        IpLimitReached() {
            super(null, null, false, false);
        }
    }
}

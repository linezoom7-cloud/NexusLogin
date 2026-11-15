package dev.nexus.login.auth;

import dev.nexus.login.account.Account;
import dev.nexus.login.menu.PinMenu;
import dev.nexus.login.ui.AuthEffects;
import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Location;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Tek bir bağlantının giriş durumu.
 *
 * <p><b>Thread kuralı:</b> {@link #state()} dışındaki tüm alanlar YALNIZCA ana thread'de okunur/yazılır.
 * Asenkron sonuçlar her zaman {@code executors.mainThread()} üzerinden buraya döner.
 * {@code state} volatile'dır çünkü AsyncChatEvent gibi asenkron eventler de onu okur.
 */
public final class AuthSession {

    private final UUID uuid;
    private final String ip;
    private final Instant startedAt;
    private final Instant deadline;

    private volatile AuthState state = AuthState.LOADING;

    private @Nullable Account account;
    private boolean waitingForPack;
    private boolean packLoaded;
    /** Paket yüklenirken menü meşguldü; iş bitince glifli görünüme geçilmeli. */
    private boolean styledRefreshPending;
    private @Nullable PinMenu menu;
    private @Nullable BossBar bossBar;
    private @Nullable Location originalLocation;
    private ItemStack @Nullable [] storedInventory;
    private @Nullable AuthEffects.Snapshot restrictionSnapshot;
    /** Proxy modunda son doğrulanmış STATE; AUTH_OK bunun connId/epoch'u ile gönderilir. */
    private dev.nexus.login.proxy.@Nullable ProxyContext proxyContext;
    /** Hesap yüklendi, akış proxy'nin STATE'ini bekliyor. */
    private boolean awaitingProxy;
    /** AUTHED'in proxy saatine göre son geçerliliği (ms); 0 = proxy modu dışı veya henüz yok. */
    private long proxyExpiresAt;

    AuthSession(UUID uuid, String ip, Duration timeout) {
        this.uuid = uuid;
        this.ip = ip;
        this.startedAt = Instant.now();
        this.deadline = startedAt.plus(timeout);
    }

    public UUID uuid() {
        return uuid;
    }

    public String ip() {
        return ip;
    }

    public AuthState state() {
        return state;
    }

    void state(AuthState newState) {
        this.state = newState;
    }

    public boolean isRestricted() {
        return state.isRestricted();
    }

    public @Nullable Account account() {
        return account;
    }

    void account(@Nullable Account newAccount) {
        this.account = newAccount;
    }

    boolean waitingForPack() {
        return waitingForPack;
    }

    void waitingForPack(boolean value) {
        this.waitingForPack = value;
    }

    public boolean packLoaded() {
        return packLoaded;
    }

    void packLoaded(boolean value) {
        this.packLoaded = value;
    }

    boolean styledRefreshPending() {
        return styledRefreshPending;
    }

    void styledRefreshPending(boolean value) {
        this.styledRefreshPending = value;
    }

    public @Nullable PinMenu menu() {
        return menu;
    }

    void menu(@Nullable PinMenu newMenu) {
        this.menu = newMenu;
    }

    public @Nullable BossBar bossBar() {
        return bossBar;
    }

    void bossBar(@Nullable BossBar newBossBar) {
        this.bossBar = newBossBar;
    }

    public @Nullable Location originalLocation() {
        return originalLocation;
    }

    void originalLocation(@Nullable Location loc) {
        this.originalLocation = loc;
    }

    public ItemStack @Nullable [] storedInventory() {
        return storedInventory;
    }

    void storedInventory(ItemStack @Nullable [] inv) {
        this.storedInventory = inv;
    }

    /** Kısıtlama uygulanmadan önceki efekt/dokunulmazlık durumu; çıkışta aynen geri konur. */
    public @Nullable AuthEffects.Snapshot restrictionSnapshot() {
        return restrictionSnapshot;
    }

    void restrictionSnapshot(@Nullable AuthEffects.Snapshot snapshot) {
        this.restrictionSnapshot = snapshot;
    }

    dev.nexus.login.proxy.@Nullable ProxyContext proxyContext() {
        return proxyContext;
    }

    void proxyContext(dev.nexus.login.proxy.@Nullable ProxyContext context) {
        this.proxyContext = context;
    }

    boolean awaitingProxy() {
        return awaitingProxy;
    }

    void awaitingProxy(boolean value) {
        this.awaitingProxy = value;
    }

    long proxyExpiresAt() {
        return proxyExpiresAt;
    }

    void proxyExpiresAt(long value) {
        this.proxyExpiresAt = value;
    }

    public boolean isExpired(Instant now) {
        return now.isAfter(deadline);
    }

    public long remainingSeconds(Instant now) {
        return Math.max(0, Duration.between(now, deadline).toSeconds());
    }

    /** BossBar ilerlemesi: 1.0 → 0.0. */
    public float remainingFraction(Instant now) {
        double total = Duration.between(startedAt, deadline).toMillis();
        double left = Duration.between(now, deadline).toMillis();
        return (float) Math.clamp(left / total, 0.0, 1.0);
    }
}

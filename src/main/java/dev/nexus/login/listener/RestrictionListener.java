package dev.nexus.login.listener;

import dev.nexus.login.auth.SessionManager;
import dev.nexus.login.menu.PinMenu;
import dev.nexus.login.ui.Messages;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityShootBowEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerCommandSendEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerHarvestBlockEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerShearEntityEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;

import java.util.Collection;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Giriş yapmamış oyuncuyu dünyadan tamamen yalıtır. LOWEST öncelik: diğer eklentiler
 * olayı zaten iptal edilmiş görür ve kendi mantıklarını çalıştırmaz.
 */
public final class RestrictionListener implements Listener {

    /**
     * Giriş kapısını açan komutlar her zaman serbesttir. Config'teki
     * {@code allowed-commands} boş bırakılsa veya yanlış yazılsa bile oyuncu
     * kapıyı yeniden açamaz hale gelmemelidir.
     */
    private static final Set<String> GATE_COMMANDS = Set.of("giris", "login", "l");

    private final SessionManager sessions;
    private final Messages messages;
    private volatile Set<String> allowedCommands;

    public RestrictionListener(SessionManager sessions, Messages messages, Set<String> allowedCommands) {
        this.sessions = sessions;
        this.messages = messages;
        this.allowedCommands = normalizeAll(allowedCommands);
    }

    /** {@code /nexus reload} sonrası komut beyaz listesini günceller. */
    public void updateAllowedCommands(Set<String> commands) {
        this.allowedCommands = normalizeAll(commands);
    }

    private static Set<String> normalizeAll(Collection<String> commands) {
        Set<String> result = new HashSet<>(GATE_COMMANDS);
        for (String command : commands) {
            String normalized = normalize(command);
            if (!normalized.isEmpty()) {
                result.add(normalized);
            }
        }
        return Set.copyOf(result);
    }

    private boolean restricted(Object entity) {
        return entity instanceof Player player && sessions.isRestricted(player);
    }

    private void deny(Player player, Cancellable event) {
        event.setCancelled(true);
        player.sendActionBar(messages.get("actionbar.locked"));
    }

    // ── hareket ──

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        // Sadece konum değişimi iptal edilir; baş çevirmek serbest (aksi halde ekran titrer).
        if (event.hasChangedPosition() && restricted(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    // ── hasar ve hedef alınma ──

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (restricted(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDamageOthers(EntityDamageByEntityEvent event) {
        Entity damager = event.getDamager();
        // Ok/kar topu/trident gibi mermilerde damager PROJECTILE'dır; asıl sahibi
        // getShooter()'dadır. Kontrol edilmezse kısıtlı oyuncu dolaylı hasar verebilirdi.
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) {
            if (restricted(shooter)) {
                event.setCancelled(true);
            }
            return;
        }
        if (restricted(damager)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onTargeted(EntityTargetLivingEntityEvent event) {
        if (restricted(event.getTarget())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onHunger(FoodLevelChangeEvent event) {
        if (restricted(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    // ── eşyalar ──

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (restricted(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (restricted(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        if (restricted(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    /**
     * Ok/yay ile dünyadaki mekanizmalar (ahşap buton, hedef bloğu, sculk sensörü) yay
     * üzerinden tetiklenebilir. Hasar zaten engelleniyor; ama buton tetiklemek de kapıyı
     * açar, bu yüzden atışın kendisi de iptal edilir.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onShootBow(EntityShootBowEvent event) {
        if (restricted(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    /** Balık tutma, envantere doğrudan eşya ekleyebildiği için ayrıca kapatılır. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (restricted(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    /** Koyun kırpma gibi doğrudan envanter kazancı sağlayan etkileşimler. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onShear(PlayerShearEntityEvent event) {
        if (restricted(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    /** Tatlı orman meyvesi gibi sağ tık hasadı her sürümde PlayerInteractEvent üretmez. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onHarvest(PlayerHarvestBlockEvent event) {
        if (restricted(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    /** Sağ tıkla binme her zaman PlayerInteractEntityEvent üretmese de araç girişi kapatılır. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onVehicleEnter(VehicleEnterEvent event) {
        if (restricted(event.getEntered())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        // PIN menüsündeki tıklamalar MenuListener'a aittir.
        if (restricted(event.getWhoClicked())
                && !(event.getView().getTopInventory().getHolder(false) instanceof PinMenu)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (restricted(event.getPlayer()) && !(event.getInventory().getHolder(false) instanceof PinMenu)) {
            event.setCancelled(true);
        }
    }

    // ── dünya etkileşimi ──

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        if (restricted(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (restricted(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    /**
     * Armor stand'lerde eşya alma/takma {@link PlayerArmorStandManipulateEvent} ile, bazı
     * etkileşimler ise {@link PlayerInteractAtEntityEvent} ile gelir; ikisi de ayrıca kapatılır.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteractAtEntity(PlayerInteractAtEntityEvent event) {
        if (restricted(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onArmorStandManipulate(PlayerArmorStandManipulateEvent event) {
        if (restricted(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        if (restricted(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        if (restricted(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    // ── sohbet ve komutlar ──

    /** Asenkron thread'de çalışır; SessionManager ve AuthSession.state bunun için thread-safe. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        if (restricted(event.getPlayer())) {
            deny(event.getPlayer(), event);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!restricted(event.getPlayer())) {
            return;
        }
        if (!allowedCommands.contains(label(event.getMessage()))) {
            deny(event.getPlayer(), event);
        }
    }

    /** Tab ile komut listesini görmesin; girişten sonra {@code player.updateCommands()} listeyi yeniler. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCommandList(PlayerCommandSendEvent event) {
        if (restricted(event.getPlayer())) {
            Set<String> allowed = allowedCommands;
            // Komut adları "nexuslogin:giris" gibi ad alanı içerebilir; ikisini de kabul et.
            event.getCommands().removeIf(command -> !allowed.contains(normalize(command)));
        }
    }

    /** "/NexusLogin:giris arg" → "giris" */
    private static String label(String message) {
        String body = message.startsWith("/") ? message.substring(1) : message;
        int space = body.indexOf(' ');
        String label = space < 0 ? body : body.substring(0, space);
        return normalize(label);
    }

    private static String normalize(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        if (value.startsWith("/")) {
            value = value.substring(1);
        }
        int colon = value.indexOf(':');
        if (colon >= 0) {
            value = value.substring(colon + 1);
        }
        return value;
    }
}

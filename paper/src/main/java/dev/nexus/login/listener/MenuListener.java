package dev.nexus.login.listener;

import dev.nexus.login.auth.AuthService;
import dev.nexus.login.menu.PinMenu;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

public final class MenuListener implements Listener {

    private final AuthService auth;

    public MenuListener(AuthService auth) {
        this.auth = auth;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof PinMenu menu)) {
            return;
        }
        // Menüdeki hiçbir item alınamaz/taşınamaz; alt envanterden shift-click ile içeri de konamaz.
        event.setCancelled(true);
        if (event.getWhoClicked() instanceof Player player
                && event.getClickedInventory() == event.getView().getTopInventory()) {
            auth.onMenuClick(player, menu, event.getRawSlot(), event.isShiftClick());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof PinMenu) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder(false) instanceof PinMenu menu && event.getPlayer() instanceof Player player) {
            auth.onMenuClosed(player, menu, event.getReason());
        }
    }
}

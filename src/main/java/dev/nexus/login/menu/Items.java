package dev.nexus.login.menu;

import dev.nexus.login.pack.PackModels;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.List;

final class Items {

    /**
     * 1.21.4+ {@code ItemMeta#setItemModel}. Derleme paper-api 1.21.1'e karşı yapıldığı için
     * API'de yoktur; bu yüzden yansıma ile çağrılır. Metot bulunamazsa (eski sunucu/istemci)
     * eşyanın kendi dokusu görünür.
     */
    private static final @Nullable Method SET_ITEM_MODEL = lookupItemModel();

    private Items() {
    }

    static ItemStack of(Material material, int amount, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(material, Math.clamp(amount, 1, 64));
        item.editMeta(meta -> decorate(meta, name, lore));
        return item;
    }

    static ItemStack of(Material material, Component name) {
        return of(material, 1, name, List.of());
    }

    static ItemStack filler(Material material) {
        return of(material, Component.empty());
    }

    /**
     * Nexus kaynak paketi yüklüyse özel item modelini uygular.
     *
     * <p>{@code fallbackAmount}: model uygulanamazsa gösterilecek adet (örn. tuş takımında
     * rakam). Model uygulandığında adet 1'e sabitlenir; aksi halde istemci köşede gereksiz
     * bir sayı çizer.
     */
    static ItemStack styled(Material fallback, int fallbackAmount, String model,
                            Component name, List<Component> lore) {
        boolean custom = SET_ITEM_MODEL != null;
        NamespacedKey key = custom ? PackModels.id(model) : null;
        ItemStack item = new ItemStack(fallback, custom ? 1 : Math.clamp(fallbackAmount, 1, 64));
        item.editMeta(meta -> {
            decorate(meta, name, lore);
            if (key != null) {
                applyModel(meta, key);
            }
        });
        return item;
    }

    static ItemStack styled(Material fallback, String model, Component name, List<Component> lore) {
        return styled(fallback, 1, model, name, lore);
    }

    private static void decorate(ItemMeta meta, Component name, List<Component> lore) {
        meta.displayName(plain(name));
        if (!lore.isEmpty()) {
            meta.lore(lore.stream().map(Items::plain).toList());
        }
        meta.addItemFlags(ItemFlag.values());
    }

    private static void applyModel(ItemMeta meta, NamespacedKey key) {
        try {
            SET_ITEM_MODEL.invoke(meta, key);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Model desteklenmiyor: temel eşya görünümüyle devam edilir.
        }
    }

    private static @Nullable Method lookupItemModel() {
        try {
            return ItemMeta.class.getMethod("setItemModel", NamespacedKey.class);
        } catch (NoSuchMethodException | SecurityException e) {
            return null;
        }
    }

    /** Item isimleri varsayılan olarak italiktir; Nexus temasında kapatılır. */
    private static Component plain(Component c) {
        return c.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }
}

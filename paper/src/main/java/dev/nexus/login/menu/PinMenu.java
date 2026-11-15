package dev.nexus.login.menu;

import dev.nexus.login.config.AuthSettings;
import dev.nexus.login.pack.PackGlyphs;
import dev.nexus.login.pack.PackModels;
import dev.nexus.login.ui.Messages;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.List;

/**
 * Tıklanabilir PIN tuş takımı. Yalnızca görünümü ve girilen rakam tamponunu yönetir;
 * doğrulama/kayıt kararları AuthService'tedir.
 *
 * <p>İki görünüm vardır:
 * <ul>
 *   <li><b>styled</b> — oyuncu Nexus kaynak paketini yüklediyse: başlıktaki özel Unicode glifi
 *       sandığın tamamını neon arka planla kaplar, sadece işlevsel item'lar konur.</li>
 *   <li><b>plain</b> — paket yoksa: aynı yerleşim renkli cam panellerle çizilir.</li>
 * </ul>
 */
public final class PinMenu implements InventoryHolder {

    private static final Material[] BORDER_BY_ROW = {
            Material.PURPLE_STAINED_GLASS_PANE, Material.PURPLE_STAINED_GLASS_PANE,
            Material.BLUE_STAINED_GLASS_PANE, Material.BLUE_STAINED_GLASS_PANE,
            Material.CYAN_STAINED_GLASS_PANE, Material.CYAN_STAINED_GLASS_PANE
    };

    /**
     * Mevcut PIN'lerin tarihsel alt sınırı. {@code AuthSettings} yeni PIN'ler için en az 4
     * hane şartı koyduğundan, bu değerin altında kayıtlı bir PIN olamaz.
     */
    private static final int LEGACY_MIN_LENGTH = 4;

    private final Messages messages;
    private final boolean styled;
    private final int minLength;
    private final int maxLength;
    private final char[] buffer;
    private final Inventory inventory;

    private PinMenuMode mode;
    private int length;
    private char @Nullable [] firstEntry;
    private boolean busy;
    private @Nullable Component notice;

    public PinMenu(Messages messages, PinMenuMode mode, boolean styled, int minLength, int maxLength) {
        this.messages = messages;
        this.mode = mode;
        this.styled = styled;
        this.minLength = minLength;
        this.maxLength = Math.max(1, Math.min(maxLength, AuthSettings.PIN_HARD_MAX));
        // Tampon HER ZAMAN donanımsal üst sınıra göre ayrılır: yapılandırılmış üst sınır
        // sonradan düşürülse bile eski ve daha uzun PIN'ler girilebilmelidir.
        this.buffer = new char[AuthSettings.PIN_HARD_MAX];
        Component title = messages.get("menu.title");
        this.inventory = Bukkit.createInventory(this, PinLayout.SIZE,
                // Kardeş bileşenler: başlık, arka plan glifinin fontunu/rengini miras ALMAMALI.
                styled ? Component.textOfChildren(PackGlyphs.pinBackground(), title) : title);
        render();
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    public boolean styled() {
        return styled;
    }

    public PinMenuMode mode() {
        return mode;
    }

    /**
     * Başlık sonradan değişmez. Paket geç yüklenince yeni bir menü açılırken
     * girilen rakamlar ve kayıt adımı bu kopyayla taşınır.
     */
    public Carry carry() {
        return new Carry(mode, Arrays.copyOf(buffer, length),
                firstEntry == null ? null : Arrays.copyOf(firstEntry, firstEntry.length),
                busy, notice);
    }

    public void restore(Carry carry) {
        this.mode = carry.mode();
        Arrays.fill(buffer, '\0');
        int count = Math.min(carry.input().length, maxEntry());
        System.arraycopy(carry.input(), 0, buffer, 0, count);
        this.length = count;
        if (firstEntry != null) {
            Arrays.fill(firstEntry, '\0');
        }
        this.firstEntry = carry.firstEntry() == null ? null : Arrays.copyOf(carry.firstEntry(), carry.firstEntry().length);
        this.busy = carry.busy();
        this.notice = carry.notice();
        // Kopyalar menüye alındı; PIN parçalarını taşıyan geçici Carry dizilerini sil.
        Arrays.fill(carry.input(), '\0');
        if (carry.firstEntry() != null) {
            Arrays.fill(carry.firstEntry(), '\0');
        }
        render();
    }

    public record Carry(PinMenuMode mode, char[] input, char @Nullable [] firstEntry, boolean busy,
                         @Nullable Component notice) {
    }

    public void mode(PinMenuMode newMode) {
        this.mode = newMode;
        render();
    }

    public boolean busy() {
        return busy;
    }

    public void busy(boolean value) {
        this.busy = value;
        render();
    }

    public int length() {
        return length;
    }

    /**
     * Onay tuşunun açılması için gereken hane sayısı.
     *
     * <p>Yeni PIN belirleyen modlarda yapılandırılmış alt sınır (varsayılan 8) geçerlidir.
     * Mevcut bir PIN'i <b>doğrulayan</b> modlarda ise eski hesaplar yeni sınırın altında
     * PIN'e sahip olabileceği için daha düşük sınır kullanılır; aksi halde alt sınır
     * yükseltildiğinde mevcut oyuncular hesaplarına giremezdi.
     */
    public int submitMinimum() {
        return switch (mode) {
            case LOGIN, CHANGE_CURRENT -> Math.min(minLength, LEGACY_MIN_LENGTH);
            case REGISTER, REGISTER_CONFIRM, CHANGE_NEW, CHANGE_CONFIRM -> minLength;
        };
    }

    public boolean meetsMinimum() {
        return length >= submitMinimum();
    }

    /**
     * Bu modda girilebilecek en fazla hane.
     * <p>Mevcut PIN'i doğrulayan modlarda donanımsal üst sınır (9) geçerlidir; aksi halde
     * {@code pin.max-length} düşürüldüğünde eski uzun PIN'ler girilemezdi. Yeni PIN
     * belirleyen modlarda ise yapılandırılmış üst sınır uygulanır.
     */
    public int maxEntry() {
        return switch (mode) {
            case LOGIN, CHANGE_CURRENT -> AuthSettings.PIN_HARD_MAX;
            case REGISTER, REGISTER_CONFIRM, CHANGE_NEW, CHANGE_CONFIRM -> maxLength;
        };
    }

    /** Gösterge satırındaki nokta sayısı: yapılandırılmış genişlik, girilen hane ile büyür. */
    private int displayCount() {
        return Math.min(AuthSettings.PIN_HARD_MAX, Math.max(maxLength, length));
    }

    // ── tampon ──

    public boolean append(char digit) {
        notice = null;
        if (length >= maxEntry()) {
            render();
            return false;
        }
        buffer[length++] = digit;
        render();
        return true;
    }

    public void backspace() {
        notice = null;
        if (length > 0) {
            buffer[--length] = '\0';
        }
        render();
    }

    public void clearInput() {
        notice = null;
        Arrays.fill(buffer, '\0');
        length = 0;
        render();
    }

    /** Girilen PIN'in bir kopyasını verir ve tamponu sıfırlar. Kopyanın sahipliği çağırana geçer. */
    public char[] takeInput() {
        char[] pin = Arrays.copyOf(buffer, length);
        Arrays.fill(buffer, '\0');
        length = 0;
        render();
        return pin;
    }

    /** Kayıt/değiştirme akışında ilk girilen PIN (onay adımında karşılaştırmak için). */
    public void firstEntry(char @Nullable [] pin) {
        if (firstEntry != null) {
            Arrays.fill(firstEntry, '\0');
        }
        firstEntry = pin;
    }

    public char @Nullable [] firstEntry() {
        return firstEntry;
    }

    /** Bilgi slotunda geçici bir hata/uyarı gösterir; ilk tuş basımında kaybolur. */
    public void notice(Component message) {
        this.notice = message;
        render();
    }

    /** Menü bırakılırken tüm PIN parçalarını bellekten siler. */
    public void wipe() {
        Arrays.fill(buffer, '\0');
        length = 0;
        firstEntry(null);
    }

    // ── çizim ──

    private void render() {
        inventory.clear();
        if (!styled) {
            renderPlainBackground();
        }
        renderDisplay();
        inventory.setItem(PinLayout.INFO_SLOT, infoItem());

        TagResolver noPlaceholders = TagResolver.empty();
        List<Component> digitLore = messages.list("menu.digit-lore", noPlaceholders);
        for (int digit = 0; digit < PinLayout.DIGIT_SLOTS.length; digit++) {
            Component name = messages.get("menu.digit", Placeholder.unparsed("digit", String.valueOf(digit)));
            ItemStack key;
            if (styled) {
                // Özel tuş kapağı: rakam dokunun içinde çizilidir, adet 1'dir.
                key = Items.styled(Material.AMETHYST_SHARD, digit == 0 ? 1 : digit,
                        PackModels.digit(digit), name, digitLore);
            } else {
                // Paket yoksa: item adedi rakamı köşede gösterir (1-9). 0 için kristal kullanılır.
                key = digit == 0
                        ? Items.of(Material.PRISMARINE_CRYSTALS, 1, name, digitLore)
                        : Items.of(Material.AMETHYST_SHARD, digit, name, digitLore);
            }
            inventory.setItem(PinLayout.DIGIT_SLOTS[digit], key);
        }

        Component deleteName = messages.get("menu.delete");
        List<Component> deleteLore = messages.list("menu.delete-lore");
        inventory.setItem(PinLayout.DELETE_SLOT, styled
                ? Items.styled(Material.MAGENTA_DYE, PackModels.DELETE, deleteName, deleteLore)
                : Items.of(Material.MAGENTA_DYE, 1, deleteName, deleteLore));

        TagResolver min = Placeholder.unparsed("min", String.valueOf(submitMinimum()));
        Component confirmName = messages.get("menu.confirm");
        List<Component> confirmLore = messages.list("menu.confirm-lore");
        Component lockedName = messages.get("menu.confirm-locked", min);
        List<Component> lockedLore = messages.list("menu.confirm-locked-lore", min);
        inventory.setItem(PinLayout.CONFIRM_SLOT, meetsMinimum()
                ? (styled
                        ? Items.styled(Material.ENDER_EYE, PackModels.CONFIRM, confirmName, confirmLore)
                        : Items.of(Material.ENDER_EYE, 1, confirmName, confirmLore))
                : (styled
                        ? Items.styled(Material.ENDER_PEARL, PackModels.CONFIRM_LOCKED, lockedName, lockedLore)
                        : Items.of(Material.ENDER_PEARL, 1, lockedName, lockedLore)));
    }

    private void renderPlainBackground() {
        ItemStack dark = Items.filler(Material.BLACK_STAINED_GLASS_PANE);
        for (int slot = 0; slot < PinLayout.SIZE; slot++) {
            int column = slot % 9;
            inventory.setItem(slot, column == 0 || column == 8
                    ? Items.filler(BORDER_BY_ROW[slot / 9])
                    : dark);
        }
    }

    private void renderDisplay() {
        int count = displayCount();
        int start = PinLayout.displayStart(count);
        if (styled) {
            // Özel nokta dokuları: dolu nokta parlar, boş nokta soluk halkadır.
            ItemStack filled = Items.styled(Material.LIGHT_BLUE_STAINED_GLASS_PANE, PackModels.DOT_FILLED,
                    messages.get("menu.filled"), List.of());
            ItemStack empty = Items.styled(Material.GRAY_STAINED_GLASS_PANE, PackModels.DOT_EMPTY,
                    messages.get("menu.empty"), List.of());
            for (int i = 0; i < count; i++) {
                inventory.setItem(start + i, i < length ? filled : empty);
            }
            return;
        }
        ItemStack filled = Items.of(Material.LIGHT_BLUE_STAINED_GLASS_PANE, messages.get("menu.filled"));
        ItemStack empty = Items.of(Material.GRAY_STAINED_GLASS_PANE, messages.get("menu.empty"));
        for (int i = 0; i < count; i++) {
            inventory.setItem(start + i, i < length ? filled : empty);
        }
    }

    private ItemStack infoItem() {
        if (busy) {
            return Items.of(Material.CLOCK, 1, messages.get("menu.busy.name"), messages.list("menu.busy.lore"));
        }
        if (notice != null) {
            return Items.of(Material.BARRIER, 1, notice, messages.list("menu.notice-lore"));
        }
        TagResolver placeholders = TagResolver.resolver(
                Placeholder.unparsed("length", String.valueOf(length)),
                Placeholder.unparsed("min", String.valueOf(submitMinimum())),
                Placeholder.unparsed("max", String.valueOf(maxEntry())));
        Material icon = mode.isGate() ? Material.NETHER_STAR : Material.TRIAL_KEY;
        Component name = messages.get(mode.messageKey() + ".name", placeholders);
        List<Component> lore = messages.list(mode.messageKey() + ".lore", placeholders);
        return styled
                ? Items.styled(icon, mode.isGate() ? PackModels.GATE : PackModels.KEY, name, lore)
                : Items.of(icon, 1, name, lore);
    }
}

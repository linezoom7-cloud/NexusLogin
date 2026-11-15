package dev.nexus.login.ui;

import dev.nexus.login.config.BrandingSettings;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * messages_{lang}.yml'yi MiniMessage ile Component'e çevirir.
 * Çoklu dil (en, tr) destekler; eksik anahtarlar JAR içindeki varsayılanlardan tamamlanır.
 * Okumalar herhangi bir thread'den güvenlidir (AsyncChatEvent dahil).
 *
 * <p>Mesaj dosyaları sabit hex renk yerine {@code ${primary}}, {@code ${accent}} gibi
 * jetonlar ve {@code <brand>}, {@code <brand_short>} etiketleri kullanabilir. Böylece
 * sunucu sahibi config.yml'den markayı değiştirdiğinde tüm dosyalar otomatik uyum sağlar.
 */
public final class Messages {

    private final JavaPlugin plugin;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    private volatile YamlConfiguration config;
    private volatile TagResolver baseResolver;
    private volatile String currentLanguage = "en";
    private volatile BrandingSettings branding;
    private volatile NexusPalette palette;

    public Messages(JavaPlugin plugin, BrandingSettings branding) {
        this.plugin = plugin;
        applyBranding(branding);
        reload();
    }

    /** Markayı canlı uygular; tam etki için ardından {@link #reload()} çağrılmalıdır. */
    public void applyBranding(BrandingSettings branding) {
        this.branding = Objects.requireNonNull(branding, "branding");
        this.palette = new NexusPalette(branding);
    }

    public void reload() {
        String lang = plugin.getConfig().getString("language", "en").trim().toLowerCase(Locale.ROOT);
        this.currentLanguage = lang;
        NexusPalette activePalette = palette;
        BrandingSettings activeBranding = branding;

        // Her iki hazır şablonu da plugins/NexusLogin klasörüne kaydet
        saveTemplate("messages_en.yml");
        saveTemplate("messages_tr.yml");

        String activeFileName = switch (lang) {
            case "tr" -> "messages_tr.yml";
            default -> "messages_en.yml";
        };

        File file = new File(plugin.getDataFolder(), activeFileName);

        YamlConfiguration loaded = YamlConfiguration.loadConfiguration(file);
        try (InputStream in = plugin.getResource(activeFileName)) {
            if (in != null) {
                loaded.setDefaults(YamlConfiguration.loadConfiguration(
                        new InputStreamReader(in, StandardCharsets.UTF_8)));
            }
        } catch (java.io.IOException e) {
            plugin.getLogger().warning("Could not read default " + activeFileName + ": " + e.getMessage());
        }

        Component prefix = miniMessage.deserialize(
                expandTokens(loaded.getString("prefix", ""), activeBranding), activePalette.tags());
        this.baseResolver = TagResolver.resolver(activePalette.tags(), Placeholder.component("prefix", prefix));
        this.config = loaded;
    }

    private void saveTemplate(String name) {
        File f = new File(plugin.getDataFolder(), name);
        if (!f.exists() && plugin.getResource(name) != null) {
            plugin.saveResource(name, false);
        }
    }

    public String currentLanguage() {
        return currentLanguage;
    }

    public BrandingSettings branding() {
        return branding;
    }

    public NexusPalette palette() {
        return palette;
    }

    public Component get(String key, TagResolver... placeholders) {
        String raw = config.getString(key);
        if (raw == null) {
            return Component.text(key);
        }
        return parse(raw, placeholders);
    }

    public List<Component> list(String key, TagResolver... placeholders) {
        return config.getStringList(key).stream().map(line -> parse(line, placeholders)).toList();
    }

    /**
     * Ham metin (MiniMessage ayrıştırılmadan). Animasyonlu başlıklarda metin parçası olarak
     * yeniden şablonlanır; bu yüzden biçimlendirme kodu içermemelidir.
     */
    public String raw(String key) {
        return config.getString(key, "");
    }

    /**
     * Ham metin değeri; anahtar yoksa/boşsa {@code fallback} döner. Yer tutucu olarak
     * kullanılan kısa değerler (ör. "evet"/"hayır") için idealdir.
     */
    public String text(String key, String fallback) {
        String value = config.getString(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    /**
     * Bağlantı kesme ekranının tamamı: marka başlığı + neden + alt bilgi.
     * Tek yerden birleştirildiği için tüm atılma yollarında görünüm aynı kalır.
     *
     * <p>Boş satırlar bilerek kodda eklenir: YAML blok metinlerinde baştaki boş satırlar
     * kırpıldığı için girintiye bağlı hizalama sunucudan sunucuya değişebilirdi.
     */
    public Component kick(String key, TagResolver... placeholders) {
        Component gap = Component.newline().append(Component.newline());
        return get("kick.header")
                .append(gap)
                .append(get(key, placeholders))
                .append(gap)
                .append(get("kick.footer"));
    }

    /** Messages dosyasından bağımsız, koddaki sabit MiniMessage şablonları için (ör. animasyon kareleri). */
    public Component parse(String raw, TagResolver... placeholders) {
        return miniMessage.deserialize(
                expandTokens(raw, branding),
                TagResolver.resolver(baseResolver, TagResolver.resolver(placeholders)));
    }

    /**
     * Mesaj şablonlarındaki marka jetonlarını genişletir. Bu işlem MiniMessage'dan ÖNCE
     * yapılır çünkü {@code <gradient>} etiketi argüman olarak başka bir etiketi kabul etmez.
     *
     * <p>Kullanılabilir jetonlar:
     * {@code ${brand}}, {@code ${brand_short}}, {@code ${brand_spaced}},
     * {@code ${primary}}, {@code ${secondary}}, {@code ${accent}},
     * {@code ${success}}, {@code ${danger}}, {@code ${muted}},
     * {@code ${gradient}}, {@code ${gradient_danger}}, {@code ${gradient_secondary}}.
     */
    public static String expandTokens(String raw, BrandingSettings branding) {
        if (raw == null || raw.isEmpty()) {
            return raw;
        }
        return raw
                .replace("${brand_spaced}", branding.spacedShortName())
                .replace("${brand_short}", branding.shortName())
                .replace("${brand}", branding.name())
                .replace("${gradient_danger}", branding.dangerHex() + ":" + branding.primaryHex())
                .replace("${gradient_secondary}", branding.secondaryHex() + ":" + branding.accentHex())
                .replace("${gradient_primary}", branding.primaryHex() + ":" + branding.accentHex())
                .replace("${gradient}", branding.gradient())
                .replace("${primary}", branding.primaryHex())
                .replace("${secondary}", branding.secondaryHex())
                .replace("${accent}", branding.accentHex())
                .replace("${success}", branding.successHex())
                .replace("${danger}", branding.dangerHex())
                .replace("${muted}", branding.mutedHex());
    }
}

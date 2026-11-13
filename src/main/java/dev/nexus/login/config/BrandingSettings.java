package dev.nexus.login.config;

import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Objects;

/**
 * White-label görünüm ayarları: görünen marka adı ve renk paleti.
 *
 * <p>Sunucu sahipleri {@code config.yml} içindeki bu bölümle "Nexus" adını ve tüm
 * vurgu renklerini değiştirebilir. {@code plugin.yml} içindeki {@code author} alanı
 * bilerek buraya bağlanmamıştır; yazar bilgisi hiçbir yapılandırmayla değiştirilemez.
 *
 * <p>Renkler {@code #RRGGBB} veya {@code RRGGBB} biçiminde okunur. Ad alanlarında
 * MiniMessage biçimlendirmesi ({@code <...>}) ve bölüm işareti ({@code §}) yasaktır:
 * aksi halde kötü niyetli/yanlış bir değer tüm mesajları bozabilirdi.
 */
public record BrandingSettings(
        String name,
        String shortName,
        int primaryRgb,
        int secondaryRgb,
        int accentRgb,
        int successRgb,
        int dangerRgb,
        int mutedRgb
) {

    public static final String DEFAULT_NAME = "Nexus Login";
    public static final String DEFAULT_SHORT_NAME = "NEXUS";

    public static final int DEFAULT_PRIMARY_RGB = 0xA855F7;    // neon mor
    public static final int DEFAULT_SECONDARY_RGB = 0x3B82F6;  // elektrik mavisi
    public static final int DEFAULT_ACCENT_RGB = 0x22D3EE;     // parlak cyan
    public static final int DEFAULT_SUCCESS_RGB = 0x4ADE80;    // başarı yeşili
    public static final int DEFAULT_DANGER_RGB = 0xF472B6;     // neon pembe (kırmızı yasak)
    public static final int DEFAULT_MUTED_RGB = 0x94A3B8;      // ikincil metin

    public static final BrandingSettings DEFAULT = new BrandingSettings(
            DEFAULT_NAME,
            DEFAULT_SHORT_NAME,
            DEFAULT_PRIMARY_RGB,
            DEFAULT_SECONDARY_RGB,
            DEFAULT_ACCENT_RGB,
            DEFAULT_SUCCESS_RGB,
            DEFAULT_DANGER_RGB,
            DEFAULT_MUTED_RGB
    );

    /**
     * Başlangıç seviyesindeki kullanıcılar için hazır tema paletleri (Presets).
     * Hex kodlarıyla tek tek uğraşmak istemeyenler config'e sadece preset adını yazabilir.
     */
    public enum Preset {
        NEXUS(DEFAULT_PRIMARY_RGB, DEFAULT_SECONDARY_RGB, DEFAULT_ACCENT_RGB, DEFAULT_SUCCESS_RGB, DEFAULT_DANGER_RGB, DEFAULT_MUTED_RGB),
        FLAME(0xEF4444, 0xF97316, 0xFBBF24, 0x4ADE80, 0xDC2626, 0x9CA3AF),      // Alev Kırmızısı & Turuncu
        EMERALD(0x10B981, 0x059669, 0x34D399, 0x22C55E, 0xF43F5E, 0x94A3B8),    // Zümrüt Yeşili
        CYBER_CYAN(0x06B6D4, 0x3B82F6, 0x38BDF8, 0x10B981, 0xEC4899, 0x94A3B8), // Siber Mavi & Cyan
        GOLDEN(0xF59E0B, 0xD97706, 0xFCD34D, 0x84CC16, 0xEF4444, 0xA8A29E),     // Asil Altın Sarısı
        ROYAL_PURPLE(0x8B5CF6, 0x6D28D9, 0xC084FC, 0x10B981, 0xF43F5E, 0x94A3B8), // Kraliyet Moru
        MIDNIGHT(0x6366F1, 0x4F46E5, 0x818CF8, 0x34D399, 0xFB7185, 0x64748B);    // Gece Laciverti

        public final int primary;
        public final int secondary;
        public final int accent;
        public final int success;
        public final int danger;
        public final int muted;

        Preset(int primary, int secondary, int accent, int success, int danger, int muted) {
            this.primary = primary;
            this.secondary = secondary;
            this.accent = accent;
            this.success = success;
            this.danger = danger;
            this.muted = muted;
        }

        public static @Nullable Preset match(@Nullable String name) {
            if (name == null || name.isBlank()) {
                return null;
            }
            String normalized = name.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
            for (Preset p : values()) {
                if (p.name().equals(normalized)) {
                    return p;
                }
            }
            return null;
        }
    }

    private static final int MAX_NAME_LENGTH = 32;
    private static final int MAX_SHORT_NAME_LENGTH = 24;

    public BrandingSettings {
        name = cleanText(name, "branding.name", MAX_NAME_LENGTH);
        shortName = cleanText(shortName, "branding.short-name", MAX_SHORT_NAME_LENGTH);
        validateColor(primaryRgb, "branding.colors.primary");
        validateColor(secondaryRgb, "branding.colors.secondary");
        validateColor(accentRgb, "branding.colors.accent");
        validateColor(successRgb, "branding.colors.success");
        validateColor(dangerRgb, "branding.colors.danger");
        validateColor(mutedRgb, "branding.colors.muted");
    }

    /**
     * Eski config dosyalarında {@code branding} bölümü yoksa Nexus varsayılanlarına döner.
     * Böylece yükseltme sırasında görünüm aniden bozulmaz.
     */
    public static BrandingSettings from(@Nullable ConfigurationSection section) {
        if (section == null) {
            return DEFAULT;
        }
        String name = section.getString("name", DEFAULT_NAME);
        String shortName = section.getString("short-name", "");
        if (shortName == null || shortName.isBlank()) {
            shortName = deriveShortName(name);
        }

        // Eğer kullanıcı hazır bir tema (preset) seçtiyse, renkleri oradan al
        String presetName = section.getString("theme", section.getString("preset"));
        Preset preset = Preset.match(presetName);

        int fallbackPrimary = preset != null ? preset.primary : DEFAULT_PRIMARY_RGB;
        int fallbackSecondary = preset != null ? preset.secondary : DEFAULT_SECONDARY_RGB;
        int fallbackAccent = preset != null ? preset.accent : DEFAULT_ACCENT_RGB;
        int fallbackSuccess = preset != null ? preset.success : DEFAULT_SUCCESS_RGB;
        int fallbackDanger = preset != null ? preset.danger : DEFAULT_DANGER_RGB;
        int fallbackMuted = preset != null ? preset.muted : DEFAULT_MUTED_RGB;

        return new BrandingSettings(
                name,
                shortName,
                color(section, "colors.primary", fallbackPrimary),
                color(section, "colors.secondary", fallbackSecondary),
                color(section, "colors.accent", fallbackAccent),
                color(section, "colors.success", fallbackSuccess),
                color(section, "colors.danger", fallbackDanger),
                color(section, "colors.muted", fallbackMuted)
        );
    }

    /** Kick ekranındaki "F L A M E" görünümü için kısa adı harf harf ayırır. */
    public String spacedShortName() {
        StringBuilder out = new StringBuilder(shortName.length() * 2);
        shortName.codePoints().forEach(cp -> {
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.appendCodePoint(cp);
        });
        return out.toString();
    }

    public String primaryHex() {
        return hex(primaryRgb);
    }

    public String secondaryHex() {
        return hex(secondaryRgb);
    }

    public String accentHex() {
        return hex(accentRgb);
    }

    public String successHex() {
        return hex(successRgb);
    }

    public String dangerHex() {
        return hex(dangerRgb);
    }

    public String mutedHex() {
        return hex(mutedRgb);
    }

    /** {@code <gradient:${gradient}>} için ana marka geçişi. */
    public String gradient() {
        return primaryHex() + ":" + secondaryHex() + ":" + accentHex();
    }

    public static String hex(int rgb) {
        return String.format(Locale.ROOT, "#%06x", rgb);
    }

    private static String cleanText(@Nullable String raw, String key, int maxLength) {
        String value = Objects.requireNonNullElse(raw, "").trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException(key + " boş olamaz");
        }
        if (value.length() > maxLength) {
            throw new IllegalArgumentException(key + " en fazla " + maxLength + " karakter olabilir");
        }
        for (char c : value.toCharArray()) {
            if (c == '<' || c == '>' || c == '&' || c == '§' || c == '\n' || c == '\r' || c == '\t') {
                throw new IllegalArgumentException(key + " MiniMessage/renk kodu veya satır sonu içeremez");
            }
        }
        return value;
    }

    private static void validateColor(int rgb, String key) {
        if (rgb < 0 || rgb > 0xFFFFFF) {
            throw new IllegalArgumentException(key + " 000000-FFFFFF aralığında olmalı");
        }
    }

    private static int color(ConfigurationSection section, String path, int fallback) {
        String raw = section.getString(path);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        String hex = raw.trim();
        if (hex.startsWith("#")) {
            hex = hex.substring(1);
        }
        if (!hex.matches("[0-9a-fA-F]{6}")) {
            throw new IllegalArgumentException("branding." + path
                    + " geçersiz renk: '" + raw + "' (örnek: #A855F7)");
        }
        return Integer.parseInt(hex, 16);
    }

    private static String deriveShortName(String name) {
        String trimmed = Objects.requireNonNullElse(name, DEFAULT_NAME).trim();
        int space = trimmed.indexOf(' ');
        String first = space > 0 ? trimmed.substring(0, space) : trimmed;
        return first.toUpperCase(Locale.ROOT);
    }
}

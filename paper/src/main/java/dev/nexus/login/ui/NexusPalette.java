package dev.nexus.login.ui;

import dev.nexus.login.config.BrandingSettings;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Color;

import java.util.Objects;

/**
 * Görsel kimliğin tek kaynağı. Metin (Adventure), partikül (Bukkit Color) ve kaynak
 * paketi görselleri (java.awt) aynı marka renklerinden beslenir.
 *
 * <p>Renkler artık sabit değil: {@link BrandingSettings} ile gelen değerlerden üretilir.
 * Sunucu sahibi "Nexus" adını ve paletini değiştirdiğinde tüm menüler, başlıklar,
 * partiküller ve kaynak paketi aynı görünümü izler.
 */
public final class NexusPalette {

    public static final NexusPalette DEFAULT = new NexusPalette(BrandingSettings.DEFAULT);

    private final BrandingSettings branding;

    private final int primaryRgb;
    private final int secondaryRgb;
    private final int accentRgb;
    private final int successRgb;
    private final int dangerRgb;
    private final int mutedRgb;

    private final TextColor primary;
    private final TextColor secondary;
    private final TextColor accent;
    private final TextColor success;
    private final TextColor danger;
    private final TextColor muted;

    private final Color primaryParticle;
    private final Color secondaryParticle;
    private final Color accentParticle;
    private final Color successParticle;
    private final Color dangerParticle;

    private final String gradient;
    private final TagResolver tags;

    public NexusPalette(BrandingSettings branding) {
        this.branding = Objects.requireNonNull(branding, "branding");
        this.primaryRgb = branding.primaryRgb();
        this.secondaryRgb = branding.secondaryRgb();
        this.accentRgb = branding.accentRgb();
        this.successRgb = branding.successRgb();
        this.dangerRgb = branding.dangerRgb();
        this.mutedRgb = branding.mutedRgb();

        this.primary = TextColor.color(primaryRgb);
        this.secondary = TextColor.color(secondaryRgb);
        this.accent = TextColor.color(accentRgb);
        this.success = TextColor.color(successRgb);
        this.danger = TextColor.color(dangerRgb);
        this.muted = TextColor.color(mutedRgb);

        this.primaryParticle = Color.fromRGB(primaryRgb);
        this.secondaryParticle = Color.fromRGB(secondaryRgb);
        this.accentParticle = Color.fromRGB(accentRgb);
        this.successParticle = Color.fromRGB(successRgb);
        this.dangerParticle = Color.fromRGB(dangerRgb);

        this.gradient = branding.gradient();
        this.tags = TagResolver.resolver(
                // Eski mesaj dosyalarıyla uyumlu adlar.
                TagResolver.resolver("neon", Tag.styling(primary)),
                TagResolver.resolver("volt", Tag.styling(secondary)),
                TagResolver.resolver("glow", Tag.styling(accent)),
                TagResolver.resolver("success", Tag.styling(success)),
                TagResolver.resolver("danger", Tag.styling(danger)),
                TagResolver.resolver("muted", Tag.styling(muted)),
                // Daha okunaklı, marka odaklı adlar (yeni dosyalar bunları kullanır).
                TagResolver.resolver("primary", Tag.styling(primary)),
                TagResolver.resolver("secondary", Tag.styling(secondary)),
                TagResolver.resolver("accent", Tag.styling(accent)),
                // Marka adı etiketleri: MiniMessage kodundan bağımsız, güvenli metin.
                TagResolver.resolver("brand", Tag.inserting(Component.text(branding.name()))),
                TagResolver.resolver("brand_short", Tag.inserting(Component.text(branding.shortName()))),
                TagResolver.resolver("brand_spaced", Tag.inserting(Component.text(branding.spacedShortName())))
        );
    }

    public static NexusPalette from(BrandingSettings branding) {
        return new NexusPalette(branding);
    }

    public BrandingSettings branding() {
        return branding;
    }

    public int primaryRgb() {
        return primaryRgb;
    }

    public int secondaryRgb() {
        return secondaryRgb;
    }

    public int accentRgb() {
        return accentRgb;
    }

    public int successRgb() {
        return successRgb;
    }

    public int dangerRgb() {
        return dangerRgb;
    }

    public int mutedRgb() {
        return mutedRgb;
    }

    public TextColor primary() {
        return primary;
    }

    public TextColor secondary() {
        return secondary;
    }

    public TextColor accent() {
        return accent;
    }

    public TextColor success() {
        return success;
    }

    public TextColor danger() {
        return danger;
    }

    public TextColor muted() {
        return muted;
    }

    public Color primaryParticle() {
        return primaryParticle;
    }

    public Color secondaryParticle() {
        return secondaryParticle;
    }

    public Color accentParticle() {
        return accentParticle;
    }

    public Color successParticle() {
        return successParticle;
    }

    public Color dangerParticle() {
        return dangerParticle;
    }

    public String primaryHex() {
        return branding.primaryHex();
    }

    public String secondaryHex() {
        return branding.secondaryHex();
    }

    public String accentHex() {
        return branding.accentHex();
    }

    public String successHex() {
        return branding.successHex();
    }

    public String dangerHex() {
        return branding.dangerHex();
    }

    public String mutedHex() {
        return branding.mutedHex();
    }

    /** Ana marka geçişi; MiniMessage gradient etiketlerinde kullanılır. */
    public String gradient() {
        return gradient;
    }

    /**
     * messages_en.yml / messages_tr.yml içinde renk ve marka etiketlerini tanımlar.
     * Örnek: {@code <primary>}, {@code <accent>}, {@code <brand>}, {@code <brand_short>}.
     */
    public TagResolver tags() {
        return tags;
    }
}

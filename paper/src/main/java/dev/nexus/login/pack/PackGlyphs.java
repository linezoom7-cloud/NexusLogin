package dev.nexus.login.pack;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * Özel fonttaki Unicode karakterleri (Private Use Area, U+E000 / U+F000).
 *
 * <p>Teknik: sandık başlığı x=8, y=6 konumundan çizilir. Başlığa önce -8 px'lik bir "space"
 * glifi, sonra 176 px genişliğinde arka plan glifi, ardından başlığı tekrar x=8'e götüren
 * negatif boşluk eklenir. Böylece arka plan GUI'nin sol üst köşesine tam oturur.
 */
public final class PackGlyphs {

    public static final Key FONT = Key.key("nexus", "gui");

    public static final int BACKGROUND_WIDTH = 176;
    /** 6 satırlık sandık GUI'sinin tam yüksekliği: başlık + 6×18 slot + oyuncu envanteri. */
    public static final int BACKGROUND_HEIGHT = 222;
    /** Başlık taban çizgisi (baseline) sandığın tepesinden 13 px aşağıdadır. */
    public static final int BACKGROUND_ASCENT = 13;

    public static final char BACKGROUND = '';
    public static final char SHIFT_TO_EDGE = '';
    public static final char SHIFT_TO_TITLE = '';

    public static final int SHIFT_TO_EDGE_ADVANCE = -8;
    /** Bitmap glifin ilerleme genişliği = genişlik + 1. Başlık tekrar x=8'den başlasın. */
    public static final int SHIFT_TO_TITLE_ADVANCE = -(BACKGROUND_WIDTH + 1 + SHIFT_TO_EDGE_ADVANCE);

    private PackGlyphs() {
    }

    /**
     * Beyaz renk zorunlu: başlık metinleri varsayılan olarak koyu gri boyanır ve bitmap glifler
     * metin rengiyle çarpılır. Beyaz = dokunun orijinal renkleri.
     */
    public static Component pinBackground() {
        return Component.text(new String(new char[]{SHIFT_TO_EDGE, BACKGROUND, SHIFT_TO_TITLE}))
                .font(FONT)
                .color(NamedTextColor.WHITE);
    }
}

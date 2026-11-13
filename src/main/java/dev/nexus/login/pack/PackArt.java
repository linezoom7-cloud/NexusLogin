package dev.nexus.login.pack;

import dev.nexus.login.menu.PinLayout;
import dev.nexus.login.ui.NexusPalette;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Arc2D;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Random;

/**
 * Kaynak paketi görsellerini kodla (prosedürel) çizer: harici PNG dosyası, font veya
 * ekran gerekmez; yalnızca şekil çizimi yapıldığı için headless Linux sunucularda da çalışır.
 *
 * <p>Kalite tekniği: her görsel {@value #SS}× büyütülmüş tuvalde çizilip yumuşak biçimde
 * hedef boyuta indirilir (supersampling). Böylece 1 px'lik neon çizgiler ve yuvarlatılmış
 * köşeler 4× daha fazla örnekle üretilir; sonuç, GUI ölçeğinde pürüzsüz görünür.
 *
 * <p>Metin/font KULLANILMAZ: rakamlar yedi parçalı çizgilerle çizilir. Bu hem platformlar
 * arası birebir aynı çıktıyı (aynı ZIP hash'ini) hem de font eksikliği olan konteynerlerde
 * güvenli çalışmayı sağlar.
 */
final class PackArt {

    /** Supersampling çarpanı: çizim bu kadar büyük yapılır, sonra indirilir. */
    private static final int SS = 4;
    /** pack.png kenar uzunluğu. */
    static final int LOGO_SIZE = 256;
    /** Özel item dokularının kenar uzunluğu (GUI'de 16×16'ya indirilir). */
    private static final int ITEM_SIZE = 64;

    private final Color NEON;
    private final Color VOLT;
    private final Color GLOW;
    private final Color DANGER;
    private final Color SUCCESS;
    private final Color MUTED;

    private final Color SLOT_INK;
    private final Color SLOT_EDGE;
    private final Color ICE = new Color(0xF2FBFF);

    private final Color BACKDROP_TOP;
    private final Color BACKDROP_MID;
    private final Color BACKDROP_BOTTOM;
    private final Color HEADER_TOP;
    private final Color HEADER_BOTTOM;
    private final Color SEPARATOR_TOP;
    private final Color SEPARATOR_BOTTOM;
    private final Color DISK_TOP;
    private final Color DISK_BOTTOM;

    /** Oyuncu envanteri satırlarının dikey başlangıcı (vanilla generic_54.png ile doğrulandı). */
    private static final int INVENTORY_TOP = 139;
    private static final int HOTBAR_TOP = 197;

    /**
     * Yedi parçalı gösterge: A üst, B sağ-üst, C sağ-alt, D alt, E sol-alt, F sol-üst, G orta.
     * İndeks = rakam.
     */
    private static final boolean[][] SEGMENTS = {
            {true, true, true, true, true, true, false},   // 0
            {false, true, true, false, false, false, false}, // 1
            {true, true, false, true, true, false, true},  // 2
            {true, true, true, true, false, false, true},  // 3
            {false, true, true, false, false, true, true}, // 4
            {true, false, true, true, false, true, true},  // 5
            {true, false, true, true, true, true, true},   // 6
            {true, true, true, false, false, false, false}, // 7
            {true, true, true, true, true, true, true},    // 8
            {true, true, true, true, false, true, true},   // 9
    };

    PackArt(NexusPalette palette) {
        this.NEON = new Color(palette.primaryRgb());
        this.VOLT = new Color(palette.secondaryRgb());
        this.GLOW = new Color(palette.accentRgb());
        this.DANGER = new Color(palette.dangerRgb());
        this.SUCCESS = new Color(palette.successRgb());
        this.MUTED = new Color(palette.mutedRgb());

        // Koyu paneller marka renginin siyahla karışımıdır: palet değişince paket de uyum sağlar.
        this.SLOT_INK = mix(NEON, Color.BLACK, 0.94f);
        this.SLOT_EDGE = mix(NEON, MUTED, 0.62f);
        this.BACKDROP_TOP = mix(NEON, Color.BLACK, 0.82f);
        this.BACKDROP_MID = mix(NEON, Color.BLACK, 0.90f);
        this.BACKDROP_BOTTOM = mix(NEON, Color.BLACK, 0.96f);
        this.HEADER_TOP = mix(NEON, Color.BLACK, 0.78f);
        this.HEADER_BOTTOM = mix(NEON, Color.BLACK, 0.92f);
        this.SEPARATOR_TOP = mix(NEON, Color.BLACK, 0.88f);
        this.SEPARATOR_BOTTOM = mix(NEON, Color.BLACK, 0.92f);
        this.DISK_TOP = mix(NEON, Color.BLACK, 0.80f);
        this.DISK_BOTTOM = mix(NEON, Color.BLACK, 0.95f);
    }

    // ═════════════════════════ arka plan ═════════════════════════

    /** 176×222 PIN menüsü arka planı: tüm GUI'yi kaplar, slot kutuları {@link PinLayout} ile hizalıdır. */
    byte[] pinBackground(int pinMaxLength) {
        int w = PackGlyphs.BACKGROUND_WIDTH;
        int h = PackGlyphs.BACKGROUND_HEIGHT;
        BufferedImage hi = new BufferedImage(w * SS, h * SS, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = hi.createGraphics();
        g.scale(SS, SS);
        smooth(g);

        background(g, w, h);
        header(g, w);
        motifs(g);
        separator(g, w);
        slots(g, pinMaxLength);
        inventorySlots(g);
        footer(g, w);
        outerFrame(g, w, h);
        cornerBrackets(g, w, h);

        g.dispose();
        return png(downscale(hi, w, h));
    }

    private void background(Graphics2D g, int w, int h) {
        // Marka renginin koyu tonlarından derin siyaha dikey geçiş.
        g.setPaint(new LinearGradientPaint(0, 0, 0, h, new float[]{0f, 0.35f, 1f},
                new Color[]{BACKDROP_TOP, BACKDROP_MID, BACKDROP_BOTTOM}));
        g.fillRect(0, 0, w, h);

        // Başlık arkasında geniş neon aura.
        g.setPaint(new RadialGradientPaint(new Point2D.Float(w / 2f, 54f), 152f,
                new float[]{0f, 0.55f, 1f},
                new Color[]{alpha(NEON, 58), alpha(VOLT, 24), alpha(VOLT, 0)}));
        g.fillRect(0, 0, w, h);

        // Klavye bölgesinin arkasında hafif cyan parıltı.
        g.setPaint(new RadialGradientPaint(new Point2D.Float(w / 2f, 90f), 74f,
                new float[]{0f, 1f}, new Color[]{alpha(GLOW, 22), alpha(GLOW, 0)}));
        g.fillRect(0, 40, w, 124);

        // Çok ince tarama çizgileri: düz gradyana doku katar.
        g.setColor(alpha(Color.BLACK, 16));
        for (int y = 1; y < h; y += 4) {
            g.fillRect(0, y, w, 1);
        }

        starfield(g, w, h, 130, 0x4E4558_5553L);
    }

    private void header(Graphics2D g, int w) {
        g.setPaint(new LinearGradientPaint(0, 0, 0, 17,
                new float[]{0f, 1f}, new Color[]{HEADER_TOP, HEADER_BOTTOM}));
        g.fillRect(1, 1, w - 2, 16);

        // Başlık altı neon çizgi.
        g.setPaint(new LinearGradientPaint(7, 0, w - 7, 0, new float[]{0f, 0.5f, 1f},
                new Color[]{alpha(NEON, 0), alpha(GLOW, 225), alpha(NEON, 0)}));
        g.fillRect(7, 16, w - 14, 1);

        // Başlığın iki yanında küçük elmas işaretler.
        diamond(g, 4.5f, 8.5f, 1.6f, GLOW, 200);
        diamond(g, w - 4.5f, 8.5f, 1.6f, GLOW, 200);
    }

    /** Boş yan sütunlardaki marka motifleri: solda portal, sağda kilit, aralarında enerji noktaları. */
    private void motifs(Graphics2D g) {
        portal(g, 34, 89, 14.5f);
        lock(g, 142, 89, 1.4f);

        g.setColor(alpha(GLOW, 130));
        for (float x = 50f; x < 59f; x += 3f) {
            g.fill(new Ellipse2D.Float(x, 88.2f, 1.6f, 1.6f));
        }
        for (float x = 118f; x < 127f; x += 3f) {
            g.fill(new Ellipse2D.Float(x, 88.2f, 1.6f, 1.6f));
        }
    }

    private void separator(Graphics2D g, int w) {
        g.setPaint(new LinearGradientPaint(0, 126, 0, 139, new float[]{0f, 1f},
                new Color[]{alpha(SEPARATOR_TOP, 0), SEPARATOR_BOTTOM}));
        g.fillRect(1, 126, w - 2, 13);

        g.setPaint(new LinearGradientPaint(9, 0, w - 9, 0, new float[]{0f, 0.5f, 1f},
                new Color[]{alpha(NEON, 0), alpha(SLOT_EDGE, 220), alpha(NEON, 0)}));
        g.fillRect(9, 131, w - 18, 1);

        diamond(g, w / 2f, 131.5f, 4.2f, GLOW, 60);
        diamond(g, w / 2f, 131.5f, 2.4f, GLOW, 235);
    }

    private void slots(Graphics2D g, int pinMaxLength) {
        int start = PinLayout.displayStart(pinMaxLength);
        for (int i = 0; i < pinMaxLength; i++) {
            slot(g, start + i, GLOW, true);
        }
        slot(g, PinLayout.INFO_SLOT, NEON, true);
        for (int slot : PinLayout.DIGIT_SLOTS) {
            slot(g, slot, VOLT, true);
        }
        slot(g, PinLayout.DELETE_SLOT, DANGER, true);
        slot(g, PinLayout.CONFIRM_SLOT, SUCCESS, true);
    }

    private void inventorySlots(Graphics2D g) {
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                slot(g, 7 + col * 18, INVENTORY_TOP + row * 18, SLOT_EDGE, false);
            }
        }
        for (int col = 0; col < 9; col++) {
            slot(g, 7 + col * 18, HOTBAR_TOP, SLOT_EDGE, false);
        }
    }

    private void slot(Graphics2D g, int slot, Color accent, boolean active) {
        slot(g, PinLayout.slotX(slot), PinLayout.slotY(slot), accent, active);
    }

    private void slot(Graphics2D g, int x, int y, Color accent, boolean active) {
        RoundRectangle2D shape = new RoundRectangle2D.Float(x + 0.5f, y + 0.5f, 17f, 17f, 4.5f, 4.5f);
        g.setColor(SLOT_INK);
        g.fill(shape);
        // Üstten alta sönümlenen iç parlama.
        g.setPaint(new GradientPaint(0, y + 1, alpha(Color.WHITE, active ? 24 : 10),
                0, y + 10, alpha(Color.WHITE, 0)));
        g.fill(shape);

        if (active) {
            // Yumuşak dış hale: 4× tuvalde kalın çizgi, indirildiğinde ince parıltıya dönüşür.
            g.setStroke(new BasicStroke(2.8f));
            g.setColor(alpha(accent, 26));
            g.draw(shape);
        }

        g.setStroke(new BasicStroke(1f));
        g.setColor(alpha(accent, active ? 220 : 95));
        g.draw(shape);

        g.setColor(alpha(Color.WHITE, active ? 36 : 14));
        g.fillRect(x + 4, y + 1, 10, 1);
    }

    private void footer(Graphics2D g, int w) {
        g.setPaint(new LinearGradientPaint(9, 0, w - 9, 0, new float[]{0f, 0.5f, 1f},
                new Color[]{alpha(NEON, 0), alpha(SLOT_EDGE, 210), alpha(NEON, 0)}));
        g.fillRect(9, 218, w - 18, 1);
        diamond(g, w / 2f, 218.5f, 1.8f, VOLT, 200);
    }

    private void outerFrame(Graphics2D g, int w, int h) {
        int[] alphas = {210, 95, 42};
        for (int i = 0; i < alphas.length; i++) {
            int a = alphas[i];
            g.setStroke(new BasicStroke(1f));
            g.setPaint(new LinearGradientPaint(0, 0, w, h, new float[]{0f, 0.5f, 1f},
                    new Color[]{alpha(NEON, a), alpha(VOLT, a), alpha(GLOW, a)}));
            g.draw(new RoundRectangle2D.Float(i + 0.5f, i + 0.5f, w - 1 - i * 2f, h - 1 - i * 2f, 8f, 8f));
        }
    }

    private void cornerBrackets(Graphics2D g, int w, int h) {
        float len = 13f;
        float in = 4f;
        g.setStroke(new BasicStroke(1.3f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER));
        g.setColor(alpha(GLOW, 165));
        // sol üst
        g.draw(new Line2D.Float(in, in + len, in, in));
        g.draw(new Line2D.Float(in, in, in + len, in));
        // sağ üst
        g.draw(new Line2D.Float(w - in, in + len, w - in, in));
        g.draw(new Line2D.Float(w - in, in, w - in - len, in));
        // sol alt
        g.draw(new Line2D.Float(in, h - in - len, in, h - in));
        g.draw(new Line2D.Float(in, h - in, in + len, h - in));
        // sağ alt
        g.draw(new Line2D.Float(w - in, h - in - len, w - in, h - in));
        g.draw(new Line2D.Float(w - in, h - in, w - in - len, h - in));
    }

    // ═════════════════════════ logo ═════════════════════════

    /** {@value #LOGO_SIZE}×{@value #LOGO_SIZE} pack.png: kaynak paketi listesinde görünen amblem. */
    byte[] logo() {
        int s = LOGO_SIZE;
        BufferedImage hi = new BufferedImage(s * SS, s * SS, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = hi.createGraphics();
        g.scale(SS, SS);
        smooth(g);

        float c = s / 2f;

        // Dış aura.
        g.setPaint(new RadialGradientPaint(new Point2D.Float(c, c), c * 0.99f, new float[]{0f, 0.55f, 1f},
                new Color[]{alpha(NEON, 165), alpha(VOLT, 70), alpha(VOLT, 0)}));
        g.fill(new Ellipse2D.Float(0, 0, s, s));

        // Amblem diski.
        g.setPaint(new RadialGradientPaint(new Point2D.Float(c, c - 18), c * 0.85f, new float[]{0f, 1f},
                new Color[]{DISK_TOP, DISK_BOTTOM}));
        g.fill(new Ellipse2D.Float(c * 0.1f, c * 0.1f, s * 0.8f, s * 0.8f));

        // Halkalar.
        for (int i = 0; i < 3; i++) {
            float r = c * (0.92f - i * 0.055f);
            int a = new int[]{215, 120, 55}[i];
            g.setStroke(new BasicStroke(i == 0 ? 2.4f : 1.1f));
            g.setPaint(new LinearGradientPaint(c - r, c - r, c + r, c + r, new float[]{0f, 0.5f, 1f},
                    new Color[]{alpha(NEON, a), alpha(VOLT, a), alpha(GLOW, a)}));
            g.draw(new Ellipse2D.Float(c - r, c - r, r * 2, r * 2));
        }

        starfield(g, s, s, 90, 0x4E4558_5553L);
        portal(g, c, c - 22, s * 0.27f);
        lock(g, c, c + 40, 2.5f);

        star(g, c - 74, c - 70, 7f, 4, alpha(GLOW, 235));
        star(g, c + 80, c - 40, 5f, 4, alpha(NEON, 210));
        star(g, c + 44, c + 82, 4f, 4, alpha(VOLT, 210));

        g.dispose();
        return png(downscale(hi, s, s));
    }

    // ═════════════════════════ özel item dokuları ═════════════════════════

    /** 0-9 tuş takımı kapakları: koyu gövde, mavi kenar, beyaz yedi parçalı rakam. */
    byte[] keycapDigit(int digit) {
        return keycap(mix(VOLT, Color.BLACK, 0.78f), mix(VOLT, Color.BLACK, 0.93f), alpha(VOLT, 255),
                g -> digitalDigit(g, digit, 4.4f, 4.0f, 7.2f, 8.0f, 1.15f));
    }

    /** ⌫ sil tuşu. */
    byte[] keycapDelete() {
        return keycap(mix(DANGER, Color.BLACK, 0.78f), mix(DANGER, Color.BLACK, 0.93f), alpha(DANGER, 255),
                this::backspaceIcon);
    }

    /** ✔ onay tuşu; {@code locked=true} ise soluk/kilitsiz görünüm. */
    byte[] keycapConfirm(boolean locked) {
        Color edge = locked ? mix(MUTED, Color.BLACK, 0.25f) : alpha(SUCCESS, 255);
        Color top = locked ? mix(MUTED, Color.BLACK, 0.82f) : mix(SUCCESS, Color.BLACK, 0.78f);
        Color bottom = locked ? mix(MUTED, Color.BLACK, 0.94f) : mix(SUCCESS, Color.BLACK, 0.93f);
        return keycap(top, bottom, edge, g -> {
            Path2D check = new Path2D.Float();
            check.moveTo(3.1f, 8.3f);
            check.lineTo(6.5f, 11.7f);
            check.lineTo(12.9f, 4.6f);
            if (locked) {
                g.setStroke(new BasicStroke(1.7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.setColor(alpha(MUTED, 220));
                g.draw(check);
            } else {
                iconStroke(g, check, SUCCESS, 1.7f);
            }
        });
    }

    /** PIN göstergesindeki nokta: dolu (parlayan) veya boş (soluk halka). */
    byte[] dot(boolean filled) {
        return itemTexture(g -> {
            if (filled) {
                // Geniş dış neon halesi
                g.setPaint(new RadialGradientPaint(new Point2D.Float(8, 8), 7.6f, new float[]{0f, 0.40f, 1f},
                        new Color[]{alpha(Color.WHITE, 250), alpha(GLOW, 180), alpha(GLOW, 0)}));
                g.fill(new Ellipse2D.Float(0.4f, 0.4f, 15.2f, 15.2f));

                // Orta renkli halka
                g.setStroke(new BasicStroke(1.2f));
                g.setColor(alpha(GLOW, 210));
                g.draw(new Ellipse2D.Float(2.5f, 2.5f, 11.0f, 11.0f));

                // Akkor beyaz çekirdek küresi
                g.setPaint(new RadialGradientPaint(new Point2D.Float(7.4f, 7.4f), 3.4f, new float[]{0f, 1f},
                        new Color[]{Color.WHITE, mix(GLOW, Color.WHITE, 0.65f)}));
                g.fill(new Ellipse2D.Float(5.0f, 5.0f, 6.0f, 6.0f));
            } else {
                // Boş nokta: cam yuva ve iç oyuk
                g.setColor(alpha(Color.BLACK, 70));
                g.fill(new Ellipse2D.Float(2.8f, 2.8f, 10.4f, 10.4f));

                g.setStroke(new BasicStroke(1.2f));
                g.setColor(alpha(MUTED, 140));
                g.draw(new Ellipse2D.Float(3.0f, 3.0f, 10.0f, 10.0f));

                g.setColor(alpha(MUTED, 75));
                g.fill(new Ellipse2D.Float(6.5f, 6.5f, 3.0f, 3.0f));
            }
        });
    }

    /** Portal (giriş) simgesi. */
    byte[] gateIcon() {
        return itemTexture(g -> portal(g, 8, 8.2f, 5.4f));
    }

    /** Anahtar (PIN değiştirme) simgesi. */
    byte[] keyIcon() {
        return itemTexture(g -> {
            Path2D shaft = new Path2D.Float();
            shaft.append(new Ellipse2D.Float(2.2f, 4.0f, 6.0f, 6.0f), false);
            shaft.append(new Line2D.Float(6.9f, 8.1f, 13.4f, 11.4f), false);
            shaft.append(new Line2D.Float(10.9f, 10.0f, 10.9f, 12.5f), false);
            shaft.append(new Line2D.Float(12.7f, 10.9f, 12.7f, 13.4f), false);
            iconStroke(g, shaft, GLOW, 1.5f);
        });
    }

    private static byte[] keycap(Color top, Color bottom, Color edge, Painter icon) {
        return itemTexture(g -> {
            // 1. Zemin Derin Gölge
            g.setColor(alpha(Color.BLACK, 140));
            g.fill(new RoundRectangle2D.Float(0.7f, 1.8f, 14.6f, 14.2f, 4.0f, 4.0f));

            // 2. Ana Tuş Gövdesi
            g.setPaint(new GradientPaint(0, 0.6f, top, 0, 15.4f, bottom));
            g.fill(new RoundRectangle2D.Float(0.7f, 0.6f, 14.6f, 14.8f, 4.0f, 4.0f));

            // 3. Cam / Akrilik Işık Yansıması (Glassmorphism Highlight)
            g.setPaint(new GradientPaint(0, 0.8f, alpha(Color.WHITE, 90), 0, 5.5f, alpha(Color.WHITE, 0)));
            g.fill(new RoundRectangle2D.Float(1.4f, 1.0f, 13.2f, 4.8f, 3.0f, 3.0f));

            // 4. Dış Neon Kenar Parıltısı (Outer Glow)
            g.setStroke(new BasicStroke(1.8f));
            g.setColor(alpha(edge, 55));
            g.draw(new RoundRectangle2D.Float(0.7f, 0.6f, 14.6f, 14.8f, 4.0f, 4.0f));

            // 5. Keskin Kenar Çizgisi
            g.setStroke(new BasicStroke(1.0f));
            g.setColor(edge);
            g.draw(new RoundRectangle2D.Float(0.7f, 0.6f, 14.6f, 14.8f, 4.0f, 4.0f));

            // 6. İç Çerçeve İnce Çizgi
            g.setStroke(new BasicStroke(0.6f));
            g.setColor(alpha(Color.WHITE, 40));
            g.draw(new RoundRectangle2D.Float(1.6f, 1.5f, 12.8f, 13.0f, 2.8f, 2.8f));

            icon.paint(g);
        });
    }

    private void digitalDigit(Graphics2D g, int digit, float x, float y, float w, float h, float t) {
        boolean[] on = SEGMENTS[digit];
        boolean[] allOn = {true, true, true, true, true, true, true};

        // Katman 1: Sönük Arka Segmentler (Gerçekçi LCD/Cyberpunk Ekran Hissi)
        g.setStroke(new BasicStroke(t, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(alpha(mix(GLOW, Color.BLACK, 0.75f), 35));
        segments(g, allOn, x, y, w, h, t);

        // Katman 2: Geniş Dış Neon Aura (Outer Bloom)
        g.setStroke(new BasicStroke(t + 2.4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(alpha(GLOW, 45));
        segments(g, on, x, y, w, h, t);

        // Katman 3: Yoğun Renk Çekirdeği (Color Core)
        g.setStroke(new BasicStroke(t + 1.1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(alpha(GLOW, 175));
        segments(g, on, x, y, w, h, t);

        // Katman 4: Akkor Beyaz Çizgi (Akkor Filament / White Core Glow)
        g.setStroke(new BasicStroke(t * 0.75f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(new Color(0xFAFFFF));
        segments(g, on, x, y, w, h, t);
    }

    private static void segments(Graphics2D g, boolean[] on, float x, float y, float w, float h, float t) {
        float p = t * 0.62f;
        if (on[0]) g.draw(new Line2D.Float(x + p, y, x + w - p, y));
        if (on[1]) g.draw(new Line2D.Float(x + w, y + p, x + w, y + h / 2 - p));
        if (on[2]) g.draw(new Line2D.Float(x + w, y + h / 2 + p, x + w, y + h - p));
        if (on[3]) g.draw(new Line2D.Float(x + p, y + h, x + w - p, y + h));
        if (on[4]) g.draw(new Line2D.Float(x, y + h / 2 + p, x, y + h - p));
        if (on[5]) g.draw(new Line2D.Float(x, y + p, x, y + h / 2 - p));
        if (on[6]) g.draw(new Line2D.Float(x + p, y + h / 2, x + w - p, y + h / 2));
    }

    private void backspaceIcon(Graphics2D g) {
        Path2D shape = new Path2D.Float();
        shape.moveTo(5.0, 4.7);
        shape.lineTo(2.7, 8.0);
        shape.lineTo(5.0, 11.3);
        shape.lineTo(12.6, 11.3);
        shape.lineTo(12.6, 4.7);
        shape.closePath();
        iconStroke(g, shape, DANGER, 1.1f);

        g.setStroke(new BasicStroke(1f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(ICE);
        g.draw(new Line2D.Float(6.7f, 6.7f, 9.6f, 9.3f));
        g.draw(new Line2D.Float(9.6f, 6.7f, 6.7f, 9.3f));
    }

    /** Beyaza yakın çekirdek + renkli dış parıltı ile ikon çizgisi. */
    private static void iconStroke(Graphics2D g, Shape shape, Color color, float t) {
        // Katman 1: Dış Hâlâ / Bloom Parıltısı
        g.setStroke(new BasicStroke(t + 2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(alpha(color, 45));
        g.draw(shape);

        // Katman 2: Canlı Renk Çekirdeği
        g.setStroke(new BasicStroke(t + 1.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(alpha(color, 160));
        g.draw(shape);

        // Katman 3: Akkor Işık Merkezi
        g.setStroke(new BasicStroke(Math.max(0.7f, t * 0.7f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(mix(color, Color.WHITE, 0.75f));
        g.draw(shape);
    }

    // ═════════════════════════ motifler ═════════════════════════

    private void portal(Graphics2D g, float cx, float cy, float radius) {
        g.setPaint(new RadialGradientPaint(new Point2D.Float(cx, cy), radius * 1.9f,
                new float[]{0f, 0.5f, 1f},
                new Color[]{alpha(GLOW, 44), alpha(NEON, 22), alpha(NEON, 0)}));
        g.fill(new Ellipse2D.Float(cx - radius * 1.9f, cy - radius * 1.9f, radius * 3.8f, radius * 3.8f));

        g.setPaint(new RadialGradientPaint(new Point2D.Float(cx, cy), radius,
                new float[]{0f, 0.55f, 1f},
                new Color[]{alpha(GLOW, 150), alpha(NEON, 90), alpha(NEON, 8)}));
        g.fill(new Ellipse2D.Float(cx - radius, cy - radius, radius * 2, radius * 2));

        int rings = 5;
        for (int i = 0; i < rings; i++) {
            float t = i / (float) (rings - 1);
            float r = radius * (0.9f - t * 0.62f);
            Color c = mix(mix(NEON, VOLT, Math.min(1f, t * 2f)), GLOW, Math.max(0f, t * 2f - 1f));
            g.setStroke(new BasicStroke(Math.max(0.9f, radius * 0.08f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(alpha(c, 110 + (int) (130 * t)));
            g.draw(new Arc2D.Float(cx - r, cy - r, r * 2, r * 2, i * 61f + 12f, 258f, Arc2D.OPEN));
        }
        star(g, cx, cy, radius * 0.17f, 4, alpha(GLOW, 235));
    }

    private void lock(Graphics2D g, float cx, float cy, float s) {
        // Halka (shackle).
        g.setStroke(new BasicStroke(2.2f * s, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(alpha(GLOW, 225));
        g.draw(new Arc2D.Float(cx - 6 * s, cy - 17 * s, 12 * s, 17 * s, 0, 180, Arc2D.OPEN));
        g.draw(new Line2D.Float(cx - 6 * s, cy - 8.5f * s, cx - 6 * s, cy - 5 * s));
        g.draw(new Line2D.Float(cx + 6 * s, cy - 8.5f * s, cx + 6 * s, cy - 5 * s));

        // Gövde arkasında yumuşak parıltı.
        g.setPaint(new RadialGradientPaint(new Point2D.Float(cx, cy + 2 * s), 18 * s,
                new float[]{0f, 1f}, new Color[]{alpha(VOLT, 70), alpha(VOLT, 0)}));
        g.fill(new RoundRectangle2D.Float(cx - 13 * s, cy - 9 * s, 26 * s, 22 * s, 6 * s, 6 * s));

        RoundRectangle2D body = new RoundRectangle2D.Float(cx - 10 * s, cy - 6 * s, 20 * s, 16 * s, 4.5f * s, 4.5f * s);
        g.setPaint(new GradientPaint(cx, cy - 6 * s, VOLT, cx, cy + 10 * s, NEON));
        g.fill(body);
        g.setStroke(new BasicStroke(1.1f * s));
        g.setColor(alpha(GLOW, 205));
        g.draw(body);

        // Anahtar deliği.
        g.setColor(new Color(0x08040F));
        g.fill(new Ellipse2D.Float(cx - 2 * s, cy - 1.5f * s, 4 * s, 4 * s));
        g.fill(new RoundRectangle2D.Float(cx - 0.9f * s, cy + 1.6f * s, 1.8f * s, 5 * s, 0.9f * s, 0.9f * s));
    }

    private static void diamond(Graphics2D g, float cx, float cy, float r, Color color, int a) {
        Path2D p = new Path2D.Float();
        p.moveTo(cx, cy - r);
        p.lineTo(cx + r, cy);
        p.lineTo(cx, cy + r);
        p.lineTo(cx - r, cy);
        p.closePath();
        g.setColor(alpha(color, a));
        g.fill(p);
    }

    private static void star(Graphics2D g, float cx, float cy, float r, int points, Color color) {
        Path2D p = new Path2D.Float();
        for (int i = 0; i < points * 2; i++) {
            double angle = Math.PI * i / points - Math.PI / 2;
            float rr = (i % 2 == 0) ? r : r * 0.36f;
            float x = (float) (cx + Math.cos(angle) * rr);
            float y = (float) (cy + Math.sin(angle) * rr);
            if (i == 0) {
                p.moveTo(x, y);
            } else {
                p.lineTo(x, y);
            }
        }
        p.closePath();
        g.setColor(color);
        g.fill(p);
    }

    private void starfield(Graphics2D g, int w, int h, int count, long seed) {
        Random random = new Random(seed); // sabit tohum: paket her açılışta aynı → aynı hash → istemci önbelleği
        Color[] tints = {NEON, VOLT, GLOW, Color.WHITE};
        for (int i = 0; i < count; i++) {
            g.setColor(alpha(tints[random.nextInt(tints.length)], 35 + random.nextInt(115)));
            g.fillRect(random.nextInt(w), random.nextInt(h), 1, 1);
        }
    }

    // ═════════════════════════ altyapı ═════════════════════════

    private interface Painter {
        void paint(Graphics2D g);
    }

    /** 16×16 mantıksal tuvali {@value #SS}× çizip {@value #ITEM_SIZE}×{@value #ITEM_SIZE} doku üretir. */
    private static byte[] itemTexture(Painter painter) {
        BufferedImage img = new BufferedImage(ITEM_SIZE, ITEM_SIZE, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.scale(SS, SS);
        smooth(g);
        painter.paint(g);
        g.dispose();
        return png(img);
    }

    private static BufferedImage downscale(BufferedImage src, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION,
                RenderingHints.VALUE_ALPHA_INTERPOLATION_QUALITY);
        g.drawImage(src, 0, 0, w, h, null);
        g.dispose();
        return out;
    }

    private static void smooth(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.setComposite(AlphaComposite.SrcOver);
    }

    private static Color alpha(Color c, int a) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), Math.clamp(a, 0, 255));
    }

    private static Color mix(Color a, Color b, float t) {
        return new Color(
                Math.round(a.getRed() + (b.getRed() - a.getRed()) * t),
                Math.round(a.getGreen() + (b.getGreen() - a.getGreen()) * t),
                Math.round(a.getBlue() + (b.getBlue() - a.getBlue()) * t));
    }

    private static byte[] png(BufferedImage img) {
        try (var out = new ByteArrayOutputStream()) {
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}

package dev.nexus.login.ui;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.title.Title;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Markanın tüm duyusal geri bildirimleri: Title, animasyonlu BossBar, partikül ve ses.
 * Yalnızca ana thread'den çağrılır.
 *
 * <p>Renkler ve marka adı {@link Messages#palette()} üzerinden okunur; {@code /nexus reload}
 * sonrası {@link #reloadBranding()} çağrılarak önceden hesaplanan kareler tazelenir.
 */
public final class AuthEffects {

    private static final int GRADIENT_FRAMES = 48;
    /** Animasyonun son sabit karesinde kullanılan buz beyazı vurgu; marka rengi değildir. */
    private static final String ICE_HEX = "#f2fbff";

    private static final Sound MENU_OPEN = sound("block.note_block.pling", 1f, 1.2f);
    private static final Sound ERROR = sound("entity.villager.no", 1f, 1f);
    private static final Sound SUCCESS = sound("entity.player.levelup", 1f, 1f);
    private static final Sound KEY_PRESS = sound("block.amethyst_block.hit", 0.8f, 1.6f);
    private static final Sound PORTAL_OPEN = sound("block.end_portal_frame.fill", 1f, 0.8f);
    private static final Sound PREMIUM = sound("block.beacon.activate", 1f, 1.4f);

    private static final Title.Times INTRO_TIMES = Title.Times.times(
            Duration.ofMillis(600), Duration.ofSeconds(4), Duration.ofMillis(800));
    private static final Title.Times SUCCESS_TIMES = Title.Times.times(
            Duration.ofMillis(250), Duration.ofMillis(2500), Duration.ofMillis(900));
    /**
     * Tarama kareleri: fade yok, kalma süresi uzun. Her kare bir öncekinin üzerine yazıldığı
     * için başlık kesintisiz akar; fade kullanılsaydı her karede görünür bir titreme olurdu.
     */
    private static final Title.Times SWEEP_TIMES = Title.Times.times(
            Duration.ZERO, Duration.ofMillis(1200), Duration.ZERO);
    /** Işık bandının kare sayısı (2 tick'te bir → ~0,6 sn akış). */
    private static final int SWEEP_FRAMES = 15;

    private final JavaPlugin plugin;
    private final Messages messages;
    /** Oyuncu verisinde "kısıtlama altında" işareti; değer = kısıtlama öncesi invulnerable. */
    private final NamespacedKey restrictedKey;

    private volatile NexusPalette palette;
    private volatile List<Component> bossBarFrames;
    private volatile Color[] ringColors;

    public AuthEffects(JavaPlugin plugin, Messages messages) {
        this.plugin = plugin;
        this.messages = messages;
        this.restrictedKey = new NamespacedKey(plugin, "restricted");
        reloadBranding();
    }

    /** {@code /nexus reload} sonrası marka rengi/adı değiştiyse önceden hesaplanan kareleri yeniler. */
    public void reloadBranding() {
        NexusPalette active = messages.palette();
        this.palette = active;
        this.ringColors = new Color[]{
                active.primaryParticle(), active.secondaryParticle(), active.accentParticle()
        };
        this.bossBarFrames = buildFrames(messages, active);
    }

    /**
     * Gradient "faz" kaydırması önceden hesaplanır: her tick MiniMessage parse etmek yerine
     * hazır kareler döndürülür. Geçiş markanın ana renkleri arasında kesintisiz akar.
     */
    private static List<Component> buildFrames(Messages messages, NexusPalette palette) {
        List<Component> frames = new ArrayList<>(GRADIENT_FRAMES);
        String brand = palette.branding().name();
        for (int i = 0; i < GRADIENT_FRAMES; i++) {
            float phase = -1f + 2f * i / GRADIENT_FRAMES;
            frames.add(messages.parse("<gradient:" + palette.gradient() + ":" + palette.primaryHex() + ":" + phase
                    + "><bold>✦ " + brand + " ✦</bold></gradient>"));
        }
        return List.copyOf(frames);
    }

    // ── kısıtlama efektleri ──

    /** Kısıtlama öncesi oyuncu durumu. Giriş/çıkışta birebir geri yüklenir. */
    public record Snapshot(@Nullable PotionEffect blindness, @Nullable PotionEffect slowness,
                           boolean invulnerable, int fireTicks) {
    }

    public Snapshot applyRestrictions(Player player) {
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        // Önceki oturum çökme/elektrik kesintisiyle bittiyse, oyuncu verisinde BİZİM sonsuz
        // körlük/yavaşlık efektlerimiz ve invulnerable=true kalmıştır. Bunları "orijinal durum"
        // sanıp girişten sonra geri yüklemek kalıcı körlük + ölümsüzlük (god mode) demekti.
        Byte leftoverInvulnerable = pdc.get(restrictedKey, PersistentDataType.BYTE);
        boolean crashed = leftoverInvulnerable != null;

        // Oyuncunun kendi (başka bir eklentiden/iksirden gelen) efektlerini ezmemek için
        // önce mevcut durumu kaydet. Kısıtlama bitince aynen geri konur.
        Snapshot snapshot = new Snapshot(
                ownEffect(player.getPotionEffect(PotionEffectType.BLINDNESS), 0, crashed),
                ownEffect(player.getPotionEffect(PotionEffectType.SLOWNESS), 255, crashed),
                crashed ? leftoverInvulnerable != 0 : player.isInvulnerable(),
                player.getFireTicks());

        // İşaret, efektlerden ÖNCE yazılır: oyuncu verisi bu ikisi arasında kaydedilse bile
        // bir sonraki girişte gerçek invulnerable değeri bilinir.
        pdc.set(restrictedKey, PersistentDataType.BYTE, (byte) (snapshot.invulnerable() ? 1 : 0));

        // ambient=false, particles=false, icon=false → ekranda iksir ikonu/partikülü görünmez.
        player.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS,
                PotionEffect.INFINITE_DURATION, 0, false, false, false));
        player.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,
                PotionEffect.INFINITE_DURATION, 255, false, false, false));
        player.setInvulnerable(true);
        player.setFireTicks(0);
        return snapshot;
    }

    /**
     * Çökme artığı tespit edildiyse, bizim imzamızı taşıyan efekt (sonsuz süre, aynı seviye,
     * partikül/ikon kapalı) orijinal durum sayılmaz.
     */
    private static @Nullable PotionEffect ownEffect(@Nullable PotionEffect effect, int amplifier, boolean crashed) {
        if (crashed && effect != null && effect.isInfinite() && effect.getAmplifier() == amplifier
                && !effect.hasParticles() && !effect.hasIcon()) {
            return null;
        }
        return effect;
    }

    public void clearRestrictions(Player player, @Nullable Snapshot snapshot) {
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        if (snapshot == null) {
            // Güvenlik ağı: kayıt yoksa (ör. eski oturum) en azından kendi efektlerimizi kaldır.
            Byte stored = pdc.get(restrictedKey, PersistentDataType.BYTE);
            player.removePotionEffect(PotionEffectType.BLINDNESS);
            player.removePotionEffect(PotionEffectType.SLOWNESS);
            player.setInvulnerable(stored != null && stored != 0);
            pdc.remove(restrictedKey);
            return;
        }
        restoreEffect(player, PotionEffectType.BLINDNESS, snapshot.blindness());
        restoreEffect(player, PotionEffectType.SLOWNESS, snapshot.slowness());
        player.setInvulnerable(snapshot.invulnerable());
        player.setFireTicks(snapshot.fireTicks());
        pdc.remove(restrictedKey);
    }

    private static void restoreEffect(Player player, PotionEffectType type, @Nullable PotionEffect previous) {
        player.removePotionEffect(type);
        if (previous != null) {
            player.addPotionEffect(previous);
        }
    }

    // ── giriş ekranı ──

    public void intro(Player player, boolean hasAccount) {
        player.showTitle(Title.title(
                messages.get("title.intro"),
                messages.get(hasAccount ? "title.intro-login" : "title.intro-register"),
                INTRO_TIMES));
    }

    public BossBar createBossBar() {
        List<Component> frames = bossBarFrames;
        return BossBar.bossBar(frames.getFirst(), 1f,
                nearestBossBarColor(palette.primaryRgb()), BossBar.Overlay.NOTCHED_12);
    }

    /** Her 2 tick'te bir çağrılır. */
    public void animate(Player player, BossBar bar, int frame, long secondsLeft, float progress) {
        List<Component> frames = bossBarFrames;
        Component title = frames.get(Math.floorMod(frame, GRADIENT_FRAMES));
        bar.name(title.append(messages.get("bossbar.suffix",
                Placeholder.unparsed("seconds", String.valueOf(secondsLeft)))));
        bar.progress(progress);
        // BossBar rengi marka rengine göre nabız atar; son 15 saniyede uyarı rengine döner.
        NexusPalette active = palette;
        bar.color(secondsLeft <= 15
                ? nearestBossBarColor(active.dangerRgb())
                : (frame / 12) % 2 == 0
                        ? nearestBossBarColor(active.primaryRgb())
                        : nearestBossBarColor(active.secondaryRgb()));

        if (frame % 2 == 0) {
            portalRing(player, frame);
        }
    }

    /** Oyuncunun etrafında dönen üç renkli portal halkası (diğer oyuncular da görür). */
    private void portalRing(Player player, int frame) {
        Color[] colors = ringColors;
        Location base = player.getLocation();
        double angle = frame * 0.22;
        double height = 0.15 + (Math.sin(frame * 0.08) + 1) * 0.9;
        // Yalnızca bekleyen oyuncuya gönderilir: world.spawnParticle yakındaki herkese gider ve
        // spawn'da toplanan N bekleyen oyuncu N*N paket üretirdi (bot akınında sunucuyu kilitler).
        for (int i = 0; i < colors.length; i++) {
            double a = angle + i * (Math.PI * 2 / colors.length);
            Location point = base.clone().add(Math.cos(a) * 0.85, height, Math.sin(a) * 0.85);
            player.spawnParticle(Particle.DUST, point, 1, 0, 0, 0, 0,
                    new Particle.DustOptions(colors[i], 1.1f));
        }
        if (frame % 6 == 0) {
            player.spawnParticle(Particle.PORTAL, base.clone().add(0, 0.9, 0), 6, 0.3, 0.5, 0.3, 0.4);
        }
    }

    // ── menü geri bildirimleri ──

    public void menuOpened(Player player) {
        player.playSound(MENU_OPEN);
    }

    public void keyPressed(Player player) {
        player.playSound(KEY_PRESS);
    }

    public void error(Player player) {
        player.playSound(ERROR);
    }

    public void actionBar(Player player, String key) {
        player.sendActionBar(messages.get(key));
    }

    // ── başarı ──

    public void success(Player player, String subtitleKey) {
        player.playSound(SUCCESS);
        player.playSound(PORTAL_OPEN);
        Component subtitle = messages.get(subtitleKey, Placeholder.unparsed("player", player.getName()));
        // Başlık artık sabit değil: marka renkli bir ışık bandı metnin üzerinden soldan sağa akar.
        NexusPalette active = palette;
        sweepTitle(player, messages.raw("title.success-line"), "PORTAL AÇILDI", subtitle,
                active.accentHex(), ICE_HEX, active.primaryHex());
        burst(player, false);
    }

    public void premium(Player player) {
        player.playSound(PREMIUM);
        player.playSound(SUCCESS);
        NexusPalette active = palette;
        sweepTitle(player, messages.raw("title.premium-line"), "PREMIUM GİRİŞ",
                messages.get("title.premium-sub"),
                active.successHex(), ICE_HEX, active.accentHex());
        player.sendActionBar(messages.get("actionbar.premium"));
        burst(player, true);
    }

    /**
     * Başlığı, üzerinde hareket eden bir ışık bandıyla canlandırır: 2 tick'te bir yeni kare
     * gönderilir, son kare normal fade süreleriyle kalıcı olarak bırakılır.
     *
     * <p>{@code rawLine} config'ten gelen DÜZ metindir (MiniMessage kodu içermemeli); renkler
     * burada üretilir, böylece metin değişse de animasyon bozulmaz.
     */
    private void sweepTitle(Player player, String rawLine, String fallback, Component subtitle,
                            String from, String highlight, String to) {
        String text = rawLine == null || rawLine.isBlank() ? fallback : rawLine.trim();
        List<Component> frames = new ArrayList<>(SWEEP_FRAMES + 1);
        for (int i = 0; i < SWEEP_FRAMES; i++) {
            float phase = -1.2f + 2.4f * i / (float) (SWEEP_FRAMES - 1);
            frames.add(messages.parse("<gradient:" + from + ":" + highlight + ":" + to + ':' + phase
                    + "><bold>✦  " + text + "  ✦</bold></gradient>"));
        }
        // Son kare: bant yok, sadece marka geçişi → animasyon bitince göz yormayan sabit başlık.
        frames.add(messages.parse("<gradient:" + from + ':' + to
                + "><bold>✦  " + text + "  ✦</bold></gradient>"));

        new BukkitRunnable() {
            private int index;

            @Override
            public void run() {
                if (!player.isOnline()) {
                    cancel();
                    return;
                }
                Component frame = frames.get(index);
                if (index == frames.size() - 1) {
                    player.showTitle(Title.title(frame, subtitle, SUCCESS_TIMES));
                    cancel();
                    return;
                }
                player.showTitle(Title.title(frame, subtitle, SWEEP_TIMES));
                index++;
            }
        }.runTaskTimer(plugin, 0L, 2L);
    }

    /** Portal açılış patlaması: spiral yükselen toz + ters portal + parıltı. */
    private void burst(Player player, boolean premium) {
        NexusPalette active = palette;
        Color[] colors = ringColors;
        Location center = player.getLocation().add(0, 1, 0);
        World world = player.getWorld();
        int points = 36;
        for (int i = 0; i < points; i++) {
            double a = i * (Math.PI * 2 / points);
            double y = (i / (double) points) * 2.0 - 0.9;
            Color color = premium
                    ? (i % 2 == 0 ? active.successParticle() : active.accentParticle())
                    : colors[i % colors.length];
            world.spawnParticle(Particle.DUST, center.clone().add(Math.cos(a) * 1.1, y, Math.sin(a) * 1.1),
                    1, 0, 0, 0, 0, new Particle.DustOptions(color, 1.4f));
        }
        world.spawnParticle(Particle.REVERSE_PORTAL, center, 70, 0.4, 0.8, 0.4, 0.06);
        world.spawnParticle(Particle.END_ROD, center, 18, 0.5, 0.7, 0.5, 0.04);
        if (premium) {
            world.spawnParticle(Particle.HAPPY_VILLAGER, center, 20, 0.6, 0.8, 0.6, 0);
        }
    }

    /**
     * Sınırlı BossBar paletinden marka rengine en yakın tonu seçer. Bukkit'in BossBar
     * renkleri sabit olduğu için RGB'yi HSB tonuna çevirip en yakın kovaya eşliyoruz.
     */
    private static BossBar.Color nearestBossBarColor(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        float[] hsb = java.awt.Color.RGBtoHSB(r, g, b, null);
        if (hsb[1] < 0.18f) {
            return BossBar.Color.WHITE;
        }
        float hue = hsb[0] * 360f;
        if (hue < 15f || hue >= 330f) {
            return BossBar.Color.PINK;
        }
        if (hue < 45f) {
            return BossBar.Color.RED;
        }
        if (hue < 70f) {
            return BossBar.Color.YELLOW;
        }
        if (hue < 165f) {
            return BossBar.Color.GREEN;
        }
        if (hue < 260f) {
            return BossBar.Color.BLUE;
        }
        return BossBar.Color.PURPLE;
    }

    private static Sound sound(String key, float volume, float pitch) {
        return Sound.sound(Key.key(key), Sound.Source.MASTER, volume, pitch);
    }
}

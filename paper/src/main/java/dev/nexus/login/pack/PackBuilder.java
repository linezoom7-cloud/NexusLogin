package dev.nexus.login.pack;

import dev.nexus.login.config.BrandingSettings;
import dev.nexus.login.ui.NexusPalette;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Kaynak paketini bellekte ZIP olarak üretir.
 *
 * <p>Not: Buradaki SHA-1, Minecraft protokolünün kaynak paketi bütünlüğü için ZORUNLU tuttuğu
 * dosya özetidir. Şifrelerle hiçbir ilgisi yoktur (şifreler yalnızca Argon2id ile saklanır).
 */
final class PackBuilder {

    /** Sabit zaman damgası: aynı içerik → bayt bayt aynı ZIP → aynı hash → istemci önbellekten kullanır. */
    private static final long FIXED_ENTRY_TIME = 1_700_000_000_000L;

    /**
     * Minecraft'ın 16 renkli sohbet paleti. Paket açıklaması yalnızca {@code §} kodlarını
     * desteklediği için marka renkleri en yakın kutuya eşlenir.
     */
    private static final int[] LEGACY_RGB = {
            0x000000, 0x0000AA, 0x00AA00, 0x00AAAA,
            0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
            0x555555, 0x5555FF, 0x55FF55, 0x55FFFF,
            0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF
    };
    private static final char[] LEGACY_CODES = {
            '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'
    };

    /**
     * 1.21–1.21.8 (kaynak paketi formatı 64 ve altı) {@code pack_format} ile
     * <em>liste</em> biçimindeki {@code supported_formats} okur.
     * 1.21.9+ (1.21.9–1.21.10 = 69, 1.21.11 = 75) {@code min_format}/{@code max_format}
     * dizilerini okur. Nesne biçimi {@code {min_inclusive, max_inclusive}} ve
     * tek sayı olarak yazılmış format, 1.21.9+ istemcide paketi geçersiz kılar;
     * font yüklenmez ve arka plan çizilmez.
     */
    private static String packMcmeta(BrandingSettings branding) {
        String description = "§" + legacyCode(branding.primaryRgb()) + branding.name()
                + " §8• §" + legacyCode(branding.accentRgb()) + "PIN Interface";
        return """
                {
                  "pack": {
                    "pack_format": 34,
                    "supported_formats": [34, 999],
                    "min_format": [34, 0],
                    "max_format": [999, 0],
                    "description": "%s"
                  }
                }
                """.formatted(jsonEscape(description));
    }

    record BuiltPack(byte[] bytes, String sha1Hex) {
    }

    private PackBuilder() {
    }

    static BuiltPack build(int pinMaxLength, NexusPalette palette, BrandingSettings branding) {
        String font = """
                {
                  "providers": [
                    {
                      "type": "bitmap",
                      "file": "nexus:font/pin_background.png",
                      "ascent": %d,
                      "height": %d,
                      "chars": ["\\u%04X"]
                    },
                    {
                      "type": "space",
                      "advances": { "\\u%04X": %d, "\\u%04X": %d }
                    }
                  ]
                }
                """.formatted(
                PackGlyphs.BACKGROUND_ASCENT, PackGlyphs.BACKGROUND_HEIGHT, (int) PackGlyphs.BACKGROUND,
                (int) PackGlyphs.SHIFT_TO_EDGE, PackGlyphs.SHIFT_TO_EDGE_ADVANCE,
                (int) PackGlyphs.SHIFT_TO_TITLE, PackGlyphs.SHIFT_TO_TITLE_ADVANCE);

        PackArt art = new PackArt(palette);
        try (var bytes = new ByteArrayOutputStream(); var zip = new ZipOutputStream(bytes)) {
            put(zip, "pack.mcmeta", packMcmeta(branding).getBytes(StandardCharsets.UTF_8));
            put(zip, "pack.png", art.logo());
            put(zip, "assets/nexus/font/gui.json", font.getBytes(StandardCharsets.UTF_8));
            put(zip, "assets/nexus/textures/font/pin_background.png", art.pinBackground(pinMaxLength));
            for (String model : PackModels.ALL) {
                putModel(zip, model, art);
            }
            zip.finish();
            byte[] data = bytes.toByteArray();
            return new BuiltPack(data, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(data)));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM SHA-1 desteklemiyor", e);
        }
    }

    /**
     * 1.21.4+ özel item modeli: item tanımı + model + doku. İstemci eşyanın görünümünü
     * doğrudan {@code minecraft:item_model} bileşeninden çözer, ayrı bir numara gerekmez.
     */
    private static void putModel(ZipOutputStream zip, String name, PackArt art) throws IOException {
        String definition = "{\"model\":{\"type\":\"minecraft:model\",\"model\":\"nexus:item/" + name + "\"}}";
        String model = "{\"parent\":\"minecraft:item/generated\",\"textures\":{\"layer0\":\"nexus:item/"
                + name + "\"}}";
        put(zip, "assets/nexus/items/" + name + ".json", definition.getBytes(StandardCharsets.UTF_8));
        put(zip, "assets/nexus/models/item/" + name + ".json", model.getBytes(StandardCharsets.UTF_8));
        put(zip, "assets/nexus/textures/item/" + name + ".png", textureFor(name, art));
    }

    private static byte[] textureFor(String name, PackArt art) {
        return switch (name) {
            case PackModels.DELETE -> art.keycapDelete();
            case PackModels.CONFIRM -> art.keycapConfirm(false);
            case PackModels.CONFIRM_LOCKED -> art.keycapConfirm(true);
            case PackModels.DOT_FILLED -> art.dot(true);
            case PackModels.DOT_EMPTY -> art.dot(false);
            case PackModels.GATE -> art.gateIcon();
            case PackModels.KEY -> art.keyIcon();
            default -> art.keycapDigit(name.charAt(name.length() - 1) - '0');
        };
    }

    private static char legacyCode(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        int best = 0;
        long bestDistance = Long.MAX_VALUE;
        for (int i = 0; i < LEGACY_RGB.length; i++) {
            int cr = (LEGACY_RGB[i] >> 16) & 0xFF;
            int cg = (LEGACY_RGB[i] >> 8) & 0xFF;
            int cb = LEGACY_RGB[i] & 0xFF;
            long distance = (long) (r - cr) * (r - cr)
                    + (long) (g - cg) * (g - cg)
                    + (long) (b - cb) * (b - cb);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return LEGACY_CODES[best];
    }

    private static String jsonEscape(String value) {
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (char c : value.toCharArray()) {
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    private static void put(ZipOutputStream zip, String name, byte[] data) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(FIXED_ENTRY_TIME);
        zip.putNextEntry(entry);
        zip.write(data);
        zip.closeEntry();
    }
}

package dev.nexus.login.pack;

import dev.nexus.login.config.BrandingSettings;
import dev.nexus.login.ui.NexusPalette;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.*;

class PackBuilderTest {

    @Test
    void buildsValidDeterministicPack() throws IOException {
        BrandingSettings branding = BrandingSettings.DEFAULT;
        NexusPalette palette = new NexusPalette(branding);
        PackBuilder.BuiltPack first = PackBuilder.build(8, palette, branding);
        PackBuilder.BuiltPack second = PackBuilder.build(8, palette, branding);
        assertEquals(first.sha1Hex(), second.sha1Hex(), "aynı içerik aynı hash'i vermeli (istemci önbelleği)");
        assertEquals(40, first.sha1Hex().length());

        Map<String, byte[]> entries = unzip(first.bytes());
        assertTrue(entries.containsKey("pack.mcmeta"));
        assertTrue(entries.containsKey("pack.png"));

        BufferedImage background = ImageIO.read(new ByteArrayInputStream(
                entries.get("assets/nexus/textures/font/pin_background.png")));
        assertEquals(PackGlyphs.BACKGROUND_WIDTH, background.getWidth());
        assertEquals(PackGlyphs.BACKGROUND_HEIGHT, background.getHeight());
        // Tam kaplama: hiçbir piksel yarı saydam kalmamalı, aksi halde altta vanilla gri doku görünür.
        for (int y = 0; y < background.getHeight(); y++) {
            for (int x = 0; x < background.getWidth(); x++) {
                assertEquals(255, background.getRGB(x, y) >>> 24, "saydam piksel: " + x + "," + y);
            }
        }

        // Özel item modelleri: item tanımı + model + doku üçlüsü eksiksiz olmalı.
        for (String model : PackModels.ALL) {
            assertTrue(entries.containsKey("assets/nexus/items/" + model + ".json"), model);
            assertTrue(entries.containsKey("assets/nexus/models/item/" + model + ".json"), model);
            BufferedImage texture = ImageIO.read(new ByteArrayInputStream(
                    entries.get("assets/nexus/textures/item/" + model + ".png")));
            assertEquals(64, texture.getWidth(), model);
            assertEquals(64, texture.getHeight(), model);
        }

        BufferedImage logo = ImageIO.read(new ByteArrayInputStream(entries.get("pack.png")));
        assertEquals(PackArt.LOGO_SIZE, logo.getWidth());

        // Görsel kontrol için: build/pack-preview/ altına PNG ve ZIP önizlemesi yazılır.
        java.nio.file.Path preview = java.nio.file.Path.of("build", "pack-preview");
        java.nio.file.Files.createDirectories(preview);
        java.nio.file.Files.write(preview.resolve("pin_background.png"),
                entries.get("assets/nexus/textures/font/pin_background.png"));
        java.nio.file.Files.write(preview.resolve("pack.png"), entries.get("pack.png"));
        java.nio.file.Files.write(preview.resolve("nexus-pack.zip"), first.bytes());

        String meta = new String(entries.get("pack.mcmeta"), StandardCharsets.UTF_8);
        assertTrue(meta.contains("\"supported_formats\": [34, 999]"), meta);
        assertTrue(meta.contains("\"min_format\": [34, 0]"), meta);
        assertTrue(meta.contains("\"max_format\": [999, 0]"), meta);
        assertFalse(meta.contains("min_inclusive"), meta);

        String font = new String(entries.get("assets/nexus/font/gui.json"), StandardCharsets.UTF_8);
        assertTrue(font.contains("\"\\uE000\""), font);
        assertTrue(font.contains("\"\\uF000\": -8"), font);
        assertTrue(font.contains("\"\\uF001\": -169"), font);
    }

    private static Map<String, byte[]> unzip(byte[] data) throws IOException {
        Map<String, byte[]> entries = new HashMap<>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(data))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                entries.put(entry.getName(), zip.readAllBytes());
            }
        }
        return entries;
    }
}

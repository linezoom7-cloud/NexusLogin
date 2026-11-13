package dev.nexus.login.ui;

import dev.nexus.login.config.BrandingSettings;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Mesaj dosyaları çalışma zamanında MiniMessage ile ayrıştırılıyor; burada YAML'ın doğru
 * okunduğu, blok metinlerin satır yapısını koruduğu ve etiketlerin geçerli olduğu doğrulanır.
 * Bozuk bir blok girinti (YAML'da tek boşluk kayması) sunucuda sessizce boş ekran üretirdi.
 */
class MessageFilesTest {

    private static final List<String> KICK_REASONS = List.of(
            "timeout", "locked", "not-ready", "database",
            "name-taken", "already-online", "unregistered", "internal");

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    @ParameterizedTest
    @ValueSource(strings = {"messages_en.yml", "messages_tr.yml"})
    void kickScreensAreComplete(String file) {
        YamlConfiguration yaml = load(file);

        assertNotNull(yaml.getString("kick.header"), file + " → kick.header");
        assertNotNull(yaml.getString("kick.footer"), file + " → kick.footer");

        for (String reason : KICK_REASONS) {
            String raw = yaml.getString("kick." + reason);
            assertNotNull(raw, file + " → kick." + reason);
            // Başlık + boş satır + iki açıklama satırı = en az 4 satır.
            assertTrue(raw.lines().count() >= 4, file + " → kick." + reason + " çok kısa");
        }

        String full = yaml.getString("kick.header", "")
                + "\n\n" + yaml.getString("kick.locked", "")
                + "\n\n" + yaml.getString("kick.footer", "");
        String plain = plain(full,
                Placeholder.unparsed("minutes", "5"),
                Placeholder.unparsed("registered", "Steve"));

        assertFalse(plain.contains("<"), file + " → çözümlenmemiş etiket kaldı");
        assertTrue(plain.lines().count() >= 8, file + " → kick ekranı beklenenden kısa");
        assertTrue(plain.contains("N E X U S"), file + " → marka başlığı yok");
        assertTrue(plain.contains("\n\n"), file + " → blok boş satırları kaybolmuş");
    }

    @ParameterizedTest
    @ValueSource(strings = {"messages_en.yml", "messages_tr.yml"})
    void animatedTitleLinesArePlainText(String file) {
        YamlConfiguration yaml = load(file);
        for (String key : List.of("title.success-line", "title.premium-line")) {
            String raw = yaml.getString(key);
            assertNotNull(raw, file + " → " + key);
            assertFalse(raw.isBlank(), file + " → " + key + " boş");
            // Animasyon kareleri bu metni kendi gradient etiketinin içine yerleştirir.
            assertFalse(raw.contains("<"), file + " → " + key + " düz metin olmalı");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"messages_en.yml", "messages_tr.yml"})
    void sweepFrameTemplateParses(String file) {
        YamlConfiguration yaml = load(file);
        String text = yaml.getString("title.success-line", "PORTAL AÇILDI");
        for (float phase : new float[]{-1.2f, -0.4f, 0f, 0.6f, 1.2f}) {
            Component frame = MINI.deserialize(
                    "<gradient:#22d3ee:#f2fbff:#a855f7:" + phase + "><bold>✦  " + text + "  ✦</bold></gradient>",
                    NexusPalette.DEFAULT.tags());
            assertFalse(PlainTextComponentSerializer.plainText().serialize(frame).isBlank(), file);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"messages_en.yml", "messages_tr.yml"})
    void adminUnlockMessagesExist(String file) {
        YamlConfiguration yaml = load(file);
        for (String key : List.of("command.admin.unlocked", "command.admin.unlocked-ip",
                "command.admin.resetpass")) {
            String raw = yaml.getString(key);
            assertNotNull(raw, file + " → " + key);
            assertFalse(raw.isBlank(), file + " → " + key + " boş");
        }

        List<String> usage = yaml.getStringList("command.admin.usage");
        assertTrue(usage.stream().anyMatch(line -> line.contains("/nexus unlock [")),
                file + " → /nexus unlock kullanımda yok");
        assertTrue(usage.stream().anyMatch(line -> line.contains("/nexus unlockip [")),
                file + " → /nexus unlockip kullanımda yok");
    }

    @ParameterizedTest
    @ValueSource(strings = {"messages_en.yml", "messages_tr.yml"})
    void adminCommonPlaceholdersAndReloadMessagesExist(String file) {
        YamlConfiguration yaml = load(file);
        for (String key : List.of("common.yes", "common.no", "common.present", "common.absent",
                "common.none", "command.admin.reloaded-restart", "command.admin.reload-failed",
                "command.admin.delete-failed")) {
            String raw = yaml.getString(key);
            assertNotNull(raw, file + " → " + key);
            assertFalse(raw.isBlank(), file + " → " + key + " boş");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"messages_en.yml", "messages_tr.yml"})
    void brandedJoinAndQuitExist(String file) {
        YamlConfiguration yaml = load(file);
        for (String key : List.of("chat.join", "chat.quit")) {
            String raw = yaml.getString(key);
            assertNotNull(raw, file + " → " + key);
            assertTrue(raw.contains("<player>"), file + " → " + key + " oyuncu yer tutucusu yok");
        }
    }

    private static String plain(String raw, TagResolver... placeholders) {
        String expanded = Messages.expandTokens(raw, BrandingSettings.DEFAULT);
        return PlainTextComponentSerializer.plainText().serialize(
                MINI.deserialize(expanded, TagResolver.resolver(NexusPalette.DEFAULT.tags(), TagResolver.resolver(placeholders))));
    }

    private static YamlConfiguration load(String name) {
        try (InputStream in = MessageFilesTest.class.getResourceAsStream("/" + name)) {
            assertNotNull(in, "kaynak bulunamadı: " + name);
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            return yaml;
        } catch (Exception e) {
            throw new AssertionError(name + " okunamadı", e);
        }
    }
}

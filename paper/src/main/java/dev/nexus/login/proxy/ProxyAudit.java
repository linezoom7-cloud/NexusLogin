package dev.nexus.login.proxy;

import org.bukkit.configuration.file.YamlConfiguration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Velocity modu açılmadan önce sunucunun gerçekten modern forwarding arkasında olduğunu denetler.
 * Sorun varsa köprü AÇILMAZ ve eklenti tek sunucu modunda (yerel PIN) çalışır: güvenli taraf.
 */
public final class ProxyAudit {

    private ProxyAudit() {
    }

    /** @param serverRoot sunucunun çalışma klasörü (config/ ve spigot.yml burada) */
    public static List<String> problems(Path serverRoot) {
        List<String> problems = new ArrayList<>();
        Path paperGlobal = serverRoot.resolve("config").resolve("paper-global.yml");
        if (Files.notExists(paperGlobal)) {
            problems.add(paperGlobal + " bulunamadı; Velocity modern forwarding doğrulanamıyor");
        } else {
            YamlConfiguration paper = YamlConfiguration.loadConfiguration(paperGlobal.toFile());
            if (!paper.getBoolean("proxies.velocity.enabled", false)) {
                problems.add("config/paper-global.yml: proxies.velocity.enabled true değil (modern forwarding kapalı)");
            }
            String secret = paper.getString("proxies.velocity.secret", "");
            if (secret == null || secret.length() < 12) {
                problems.add("config/paper-global.yml: proxies.velocity.secret boş veya çok kısa");
            }
        }
        Path spigot = serverRoot.resolve("spigot.yml");
        if (Files.exists(spigot) && YamlConfiguration.loadConfiguration(spigot.toFile()).getBoolean("settings.bungeecord", false)) {
            problems.add("spigot.yml: settings.bungeecord true (legacy forwarding imzasızdır, desteklenmez)");
        }
        return problems;
    }
}

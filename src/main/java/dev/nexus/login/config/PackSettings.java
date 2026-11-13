package dev.nexus.login.config;

import org.bukkit.configuration.ConfigurationSection;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * Eklentinin kendi ürettiği kaynak paketi ve dağıtım biçimi.
 *
 * @param delivery   paketin nasıl sunulacağı (bkz. {@link PackDelivery})
 * @param port       yalnızca {@link PackDelivery#PORT} için gömülü HTTP sunucusunun portu
 * @param publicHost boşsa, oyuncunun bağlanırken yazdığı adres kullanılır (ör. "oyna.sunucum.com")
 * @param publicUrl  yalnızca {@link PackDelivery#URL} için paketin tam adresi
 */
public record PackSettings(boolean enabled, PackDelivery delivery, int port, String publicHost,
                           String publicUrl, boolean required) {

    public PackSettings {
        publicHost = publicHost == null ? "" : publicHost.trim();
        publicUrl = publicUrl == null ? "" : publicUrl.trim();

        // Paket kapalıyken port/adres alanları hiç kullanılmaz. Bu durumda hatalı bir
        // artık değer yüzünden eklentinin açılmaması yanlış olur: kullanıcı paketi
        // bilerek kapatmıştır. Yine de normalizasyon (trim) her zaman yapılır.
        if (enabled) {
            if (port < 1 || port > 65_535) {
                throw new IllegalArgumentException("resource-pack.port 1-65535 aralığında olmalı");
            }
            if (delivery == PackDelivery.URL) {
                if (publicUrl.isEmpty()) {
                    throw new IllegalArgumentException(
                            "resource-pack.delivery=URL kullanılıyorsa resource-pack.public-url zorunlu");
                }
                URI uri;
                try {
                    uri = new URI(publicUrl);
                } catch (URISyntaxException e) {
                    throw new IllegalArgumentException(
                            "resource-pack.public-url geçerli bir adres değil: '" + publicUrl + "'", e);
                }
                String scheme = uri.getScheme();
                if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                    throw new IllegalArgumentException(
                            "resource-pack.public-url http:// veya https:// ile başlamalı");
                }
                if (uri.getHost() == null || uri.getHost().isBlank()) {
                    throw new IllegalArgumentException(
                            "resource-pack.public-url geçerli bir sunucu adı içermiyor: '" + publicUrl + "'");
                }
            }
        }
    }

    static PackSettings from(ConfigurationSection s) {
        return new PackSettings(
                s.getBoolean("enabled", true),
                PackDelivery.parse(s.getString("delivery", PackDelivery.SAME_PORT.id())),
                s.getInt("port", 8164),
                s.getString("public-host", ""),
                s.getString("public-url", ""),
                s.getBoolean("required", false)
        );
    }

    /** Paketin istemcilere nasıl ulaştırılacağı. */
    public enum PackDelivery {

        /**
         * Minecraft'ın kendi portu üzerinden HTTP olarak sunulur. Ek port, güvenlik duvarı
         * kuralı veya panel ayarı GEREKMEZ; paylaşımlı barındırmalarda varsayılan budur.
         */
        SAME_PORT("same-port"),

        /** Ayrı bir TCP portunda gömülü HTTP sunucusu (o port dışarıya açıksa). */
        PORT("port"),

        /** Paket başka bir adreste barındırılıyorsa (ör. kendi web sunucusu/CDN). */
        URL("url");

        private final String id;

        PackDelivery(String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }

        static PackDelivery parse(String raw) {
            String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT)
                    .replace('_', '-').replace(' ', '-');
            return switch (value) {
                case "", "same-port", "sameport", "shared", "minecraft-port" -> SAME_PORT;
                case "port", "separate-port", "http" -> PORT;
                case "url", "external", "cdn" -> URL;
                default -> throw new IllegalArgumentException(
                        "resource-pack.delivery geçersiz: '" + raw + "' (same-port, port veya url olmalı)");
            };
        }
    }
}

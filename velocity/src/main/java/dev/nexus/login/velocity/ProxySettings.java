package dev.nexus.login.velocity;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * {@code plugins/nexuslogin/config.properties}. Bağımlılık eklememek için düz Properties kullanılır.
 *
 * @param authServers      doğrulanmamış oyuncunun bulunabileceği tek sunucular (NexusLogin'li Paper)
 * @param afterLoginServer giriş sonrası gidilecek sunucu; boşsa Velocity'nin "try" listesindeki ilk auth dışı sunucu
 * @param allowedCommands  doğrulanmamış oyuncunun proxy üzerinden çalıştırabileceği komutlar (ilk kelime, tam eşleşme)
 * @param authedTtl        AUTHED durumunun backend'deki ömrü; heartbeat bunu yeniler
 * @param heartbeat        AUTHED push aralığı (ttl'den kısa olmalı)
 */
public record ProxySettings(Set<String> authServers, String afterLoginServer, Set<String> allowedCommands,
                            Duration authedTtl, Duration heartbeat, String kickNoAuthServer, String denyMessage,
                            boolean authServersShareDatabase) {

    static final String DEFAULTS = """
            # NexusLogin Velocity ayarları. Değişiklik için proxy'yi yeniden başlatın.
            # Doğrulanmamış oyuncunun bulunabileceği sunucular (velocity.toml [servers] adları, virgülle).
            auth-servers=auth
            # Birden fazla auth sunucusu YALNIZCA hepsi aynı MariaDB'yi kullanıyorsa güvenlidir; ayrı
            # veritabanında oyuncu bir auth sunucusu kapalıyken diğerinde başkasının adıyla yeni PIN
            # kaydedebilir. Aynı veritabanını kullandığınızı onaylamak için true yapın.
            auth-servers-share-database=false
            # Giriş sonrası gidilecek sunucu. Boş: velocity.toml 'try' listesindeki ilk auth dışı sunucu.
            after-login-server=
            # Girişten önce izin verilen komutlar (ilk kelime, büyük/küçük harf duyarsız, tam eşleşme).
            allowed-commands=login,l,giris,register,reg
            # AUTHED durumunun backend'de geçerli kaldığı süre ve yenileme aralığı (saniye).
            authed-ttl-seconds=45
            heartbeat-seconds=15
            kick-no-auth-server=Login server is not available. Please try again later.
            deny-message=Please log in first.
            """;

    public ProxySettings {
        if (authServers.isEmpty()) {
            throw new IllegalArgumentException("auth-servers boş olamaz");
        }
        if (heartbeat.isZero() || heartbeat.isNegative() || heartbeat.compareTo(authedTtl) >= 0) {
            throw new IllegalArgumentException("heartbeat-seconds, authed-ttl-seconds'tan küçük ve pozitif olmalı");
        }
        if (authServers.size() > 1 && !authServersShareDatabase) {
            throw new IllegalArgumentException("auth-servers birden fazla sunucu içeriyor; hepsi AYNI MariaDB veritabanını"
                    + " kullanmıyorsa hesap ele geçirilebilir. Aynı veritabanıysa auth-servers-share-database=true yapın.");
        }
        if (authServers.contains(afterLoginServer)) {
            throw new IllegalArgumentException("after-login-server bir auth sunucusu olamaz");
        }
    }

    public static ProxySettings load(Path dataDirectory) throws IOException {
        Files.createDirectories(dataDirectory);
        Path file = dataDirectory.resolve("config.properties");
        if (Files.notExists(file)) {
            try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                w.write(DEFAULTS);
            }
        }
        Properties p = new Properties();
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            p.load(r);
        }
        return from(p);
    }

    static ProxySettings from(Properties p) {
        Properties defaults = new Properties();
        try {
            defaults.load(new java.io.StringReader(DEFAULTS));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        java.util.function.Function<String, String> get = key -> p.getProperty(key, defaults.getProperty(key)).strip();
        return new ProxySettings(
                list(get.apply("auth-servers"), false),
                get.apply("after-login-server"),
                list(get.apply("allowed-commands"), true),
                Duration.ofSeconds(Long.parseLong(get.apply("authed-ttl-seconds"))),
                Duration.ofSeconds(Long.parseLong(get.apply("heartbeat-seconds"))),
                get.apply("kick-no-auth-server"),
                get.apply("deny-message"),
                Boolean.parseBoolean(get.apply("auth-servers-share-database")));
    }

    private static Set<String> list(String value, boolean lowerCase) {
        return Arrays.stream(value.split(","))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .map(s -> lowerCase ? s.toLowerCase(Locale.ROOT) : s)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}

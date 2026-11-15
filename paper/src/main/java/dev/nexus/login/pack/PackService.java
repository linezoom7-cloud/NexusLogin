package dev.nexus.login.pack;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.nexus.login.config.BrandingSettings;
import dev.nexus.login.config.PackSettings;
import dev.nexus.login.config.PackSettings.PackDelivery;
import dev.nexus.login.ui.Messages;
import dev.nexus.login.ui.NexusPalette;
import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Üretilen paketi istemcilere ulaştırır.
 *
 * <p>Varsayılan yöntem ({@code same-port}) paketi <b>Minecraft'ın kendi portu</b> üzerinden
 * HTTP ile sunar: paylaşımlı barındırmalarda (Pterodactyl/MCSH vb.) yalnızca sunucunun
 * atanmış portu dışarıya açıktır, bu yüzden ek port istemek zorunda kalmayız.
 * Alternatif olarak ayrı bir portta gömülü HTTP sunucusu ({@code port}) veya harici bir
 * adres ({@code url}) kullanılabilir.
 */
public final class PackService implements AutoCloseable {

    private static final String PATH = SamePortPackServer.PATH;
    /** Sabit kimlik: istemci yeni sürümü aynı paketin güncellemesi olarak görür. */
    private static final UUID PACK_ID = UUID.nameUUIDFromBytes("nexus-login:gui".getBytes(StandardCharsets.UTF_8));

    private final PackSettings settings;
    private final Messages messages;
    private final Logger logger;
    private final BrandingSettings branding;
    private final NexusPalette palette;

    private volatile PackBuilder.@Nullable BuiltPack pack;
    private volatile @Nullable SamePortPackServer samePort;
    private volatile @Nullable HttpServer server;
    /** ZIP'in kopyalanacağı klasör (harici barındırma / delivery: url için). */
    private @Nullable Path exportDir;
    /** Son gönderilen paket adresi: indirme hatasında konsola yazmak için. */
    private final Map<UUID, String> lastUrls = new ConcurrentHashMap<>();
    /** {@link #start} (G/Ç thread'i) ile {@link #close} (ana thread) arasındaki yarışı kapatır. */
    private final Object lifecycle = new Object();
    private volatile boolean closed;

    public PackService(PackSettings settings, Messages messages, Logger logger, BrandingSettings branding) {
        this.settings = settings;
        this.messages = messages;
        this.logger = logger;
        this.branding = branding;
        this.palette = new NexusPalette(branding);
    }

    /** Üretilen ZIP'i bu klasöre de yazar; böylece paket harici bir adrese yüklenebilir. */
    public void setExportDirectory(@Nullable Path dir) {
        this.exportDir = dir;
    }

    /**
     * Minecraft portu üzerinden sunum kancasını kurar. <b>Ana thread'den</b> çağrılmalıdır
     * (Paper'ın kanal dinleyici listesi eşzamanlı yazmaya karşı korumalı değildir).
     * Paket henüz üretilmemiş olabilir; baytlar istemci isteği geldiğinde okunur.
     */
    public void prepare() {
        if (settings.delivery() != PackDelivery.SAME_PORT) {
            return;
        }
        synchronized (lifecycle) {
            if (closed) {
                return;
            }
            samePort = SamePortPackServer.install(this::bytes, logger);
        }
    }

    /** G/Ç thread'inde çağrılır: görselleri çizer, ZIP'i üretir, gerekirse HTTP sunucusunu açar. */
    public void start(int pinMaxLength, Executor httpExecutor) throws IOException {
        PackBuilder.BuiltPack built = PackBuilder.build(pinMaxLength, palette, branding);

        synchronized (lifecycle) {
            // Eklenti, paket üretilirken kapatılmış olabilir: bu durumda soket açma.
            if (closed) {
                return;
            }
            this.pack = built;

            String size = built.bytes().length / 1024 + " KB";
            String hash = built.sha1Hex().substring(0, 10) + "…";
            exportZip(built);
            logger.info(() -> "Paket SHA-1 (harici barındırma ile karşılaştırmak için): " + built.sha1Hex());

            if (settings.delivery() == PackDelivery.URL) {
                logger.info(() -> "Kaynak paketi hazır (" + size + ", sha1=" + hash + ") — harici adres: "
                        + settings.publicUrl());
                return;
            }

            if (settings.delivery() == PackDelivery.SAME_PORT && samePort != null) {
                logger.info(() -> "Kaynak paketi hazır (" + size + ", sha1=" + hash
                        + ") — Minecraft portu üzerinden sunuluyor.");
                return;
            }

            if (settings.delivery() == PackDelivery.SAME_PORT) {
                logger.warning("Minecraft portu üzerinden sunum kurulamadı; ayrı port deneniyor ("
                        + settings.port() + "). Bu port dışarıya açık değilse oyuncular yedek menüyü görür.");
            }

            HttpServer http = HttpServer.create(new InetSocketAddress(settings.port()), 0);
            http.createContext(PATH, this::handle);
            http.setExecutor(httpExecutor);
            http.start();
            this.server = http;
            logger.info(() -> "Kaynak paketi hazır (" + size + ", sha1=" + hash + ") — HTTP portu "
                    + settings.port() + ".");
        }
    }

    private byte @Nullable [] bytes() {
        PackBuilder.BuiltPack current = pack;
        return current == null ? null : current.bytes();
    }

    /** ZIP'i diskte bırakır: panelde yalnızca tek port açık olsa bile harici adrese yüklenebilsin. */
    private void exportZip(PackBuilder.BuiltPack built) {
        Path dir = exportDir;
        if (dir == null) {
            return;
        }
        try {
            Files.createDirectories(dir);
            Path file = dir.resolve("nexus-pack.zip");
            Files.write(file, built.bytes());
            logger.info(() -> "Kaynak paketi diske yazıldı: " + file.toAbsolutePath()
                    + " (harici barındırmak için bu dosyayı yükleyip delivery: url kullanın)");
        } catch (IOException e) {
            logger.log(Level.WARNING, "Kaynak paketi diske yazılamadı: " + dir, e);
        }
    }

    /**
     * Paket sunumunu yerelden doğrular. Sunucu soketi bağlandıktan <b>sonra</b> çağrılmalıdır
     * (eklenti açılışında değil), aksi halde yanlış negatif uyarı üretir.
     */
    public void verifyDelivery() {
        if (pack == null) {
            return;
        }
        switch (settings.delivery()) {
            case SAME_PORT -> {
                if (samePort != null) {
                    verifySamePort();
                }
            }
            case PORT -> verifyStandalone();
            case URL -> logger.info(() -> "Kaynak paketi harici adresten sunulacak: " + settings.publicUrl()
                    + " (adresin dışarıdan erişilebilir olduğundan emin olun)");
        }
    }

    /** Ayrı HTTP sunucusunun gerçekten paketi döndürdüğünü yerelden doğrular. */
    private void verifyStandalone() {
        if (server == null) {
            return;
        }
        int port = settings.port();
        byte[] expected = bytes();
        if (expected == null || port <= 0) {
            return;
        }
        for (InetAddress address : loopbackCandidates()) {
            try {
                String outcome = requestPack(address, port, expected.length);
                logger.info(() -> "Ayrı HTTP portu üzerinden paket sunumu doğrulandı: " + outcome);
                return;
            } catch (IOException | RuntimeException e) {
                // Sıradaki adresi dene; hepsi başarısız olursa tek bir uyarı verilir.
            }
        }
        logger.warning("Ayrı HTTP portu (" + port + ") üzerinden paket sunumu yerelden doğrulanamadı. "
                + "Güvenlik duvarı/port yönlendirmesi bu portu engelliyor olabilir; oyuncular paketi indiremezse "
                + "resource-pack.delivery: same-port deneyin.");
    }

    /**
     * Sunucunun kendi Minecraft listener'ına HTTP isteği atarak paket sunumunu doğrular.
     * Böylece "paket çalışmıyor" sorunu, oyuncu bağlanmadan önce konsolda net biçimde görünür.
     */
    private void verifySamePort() {
        byte[] expected = bytes();
        if (expected == null) {
            return;
        }
        int port = Bukkit.getServer().getPort();
        if (port <= 0) {
            return;
        }
        for (InetAddress address : loopbackCandidates()) {
            try {
                String outcome = requestPack(address, port, expected.length);
                logger.info(() -> "Aynı port üzerinden paket sunumu doğrulandı: " + outcome);
                return;
            } catch (IOException | RuntimeException e) {
                // Sıradaki adresi dene; hepsi başarısız olursa aşağıda tek bir uyarı verilir.
            }
        }
        logger.warning("Aynı port üzerinden paket sunumu yerelden doğrulanamadı (port yönlendirmesi "
                + "farklı olabilir). Oyuncular paketi indiremezse resource-pack.delivery: port veya url deneyin.");
    }

    private static List<InetAddress> loopbackCandidates() {
        List<InetAddress> addresses = new ArrayList<>(3);
        try {
            addresses.add(InetAddress.getByName("127.0.0.1"));
        } catch (IOException ignored) {
            // yok sayılır
        }
        try {
            addresses.add(InetAddress.getByName("::1"));
        } catch (IOException ignored) {
            // yok sayılır
        }
        String ip = Bukkit.getIp();
        if (!ip.isBlank() && !"0.0.0.0".equals(ip) && !"::".equals(ip)) {
            try {
                addresses.add(InetAddress.getByName(ip));
            } catch (IOException ignored) {
                // yok sayılır
            }
        }
        return addresses;
    }

    private String requestPack(InetAddress address, int port, int expectedBytes) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(address, port), 3_000);
            socket.setSoTimeout(3_000);
            OutputStream out = socket.getOutputStream();
            out.write(("GET " + PATH + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            byte[] response = socket.getInputStream().readAllBytes();
            String head = new String(response, StandardCharsets.ISO_8859_1);
            if (!head.startsWith("HTTP/1.1 200")) {
                throw new IOException("HTTP yanıtı 200 değil");
            }
            int bodyStart = head.indexOf("\r\n\r\n");
            if (bodyStart < 0 || response.length - bodyStart - 4 != expectedBytes) {
                throw new IOException("paket boyutu eşleşmedi");
            }
            return expectedBytes + " bayt, " + address.getHostAddress() + ":" + port;
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            PackBuilder.BuiltPack current = pack;
            if (current == null || !"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(404, -1);
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", "application/zip");
            exchange.getResponseHeaders().set("Cache-Control", "public, max-age=31536000, immutable");
            exchange.sendResponseHeaders(200, current.bytes().length);
            try (OutputStream body = exchange.getResponseBody()) {
                body.write(current.bytes());
            }
        }
    }

    /** Paket üretildi mi? (Sunum yönteminden bağımsız.) */
    public boolean isActive() {
        return pack != null;
    }

    /** Log mesajları için etkin dağıtım yöntemi (ör. "same-port"). */
    public String deliveryId() {
        return settings.delivery().id();
    }

    public boolean isOurPack(UUID id) {
        return PACK_ID.equals(id);
    }

    /** @return paket gönderildiyse {@code true}; çağıran, durum eventini beklemeli */
    public boolean send(Player player) {
        PackBuilder.BuiltPack current = pack;
        if (current == null) {
            return false;
        }
        String url = urlFor(player, current);
        if (url == null) {
            return false;
        }
        try {
            URI uri = URI.create(url);
            ResourcePackRequest request = ResourcePackRequest.resourcePackRequest()
                    .packs(ResourcePackInfo.resourcePackInfo(PACK_ID, uri, current.sha1Hex()))
                    .required(settings.required())
                    .replace(false)
                    .prompt(messages.get("actionbar.pack-loading"))
                    .build();
            player.sendResourcePacks(request);
            lastUrls.put(player.getUniqueId(), uri.toString());
            logger.info(() -> "Kaynak paketi istendi: " + player.getName() + " → " + uri);
            return true;
        } catch (IllegalArgumentException e) {
            logger.log(Level.WARNING, "Kaynak paketi adresi geçersiz: " + url, e);
            return false;
        }
    }

    /** İndirme hatasında konsola yazılacak son paket adresi. */
    public @Nullable String lastUrl(UUID playerId) {
        return lastUrls.get(playerId);
    }

    /** Oyuncu çıktığında tanılama kaydını temizler. */
    public void forget(UUID playerId) {
        lastUrls.remove(playerId);
    }

    private @Nullable String urlFor(Player player, PackBuilder.BuiltPack current) {
        if (settings.delivery() == PackDelivery.URL) {
            return settings.publicUrl();
        }
        boolean standalone = server != null;
        if (settings.delivery() == PackDelivery.PORT && !standalone) {
            return null;
        }
        if (settings.delivery() == PackDelivery.SAME_PORT && samePort == null && !standalone) {
            return null;
        }
        Target target = resolveTarget(player, standalone);
        if (target == null) {
            return null;
        }
        return "http://" + target.host() + ":" + target.port() + PATH
                + "?v=" + current.sha1Hex().substring(0, 12);
    }

    private record Target(String host, int port) {
    }

    /**
     * Paket adresinin hedefini bulur. Öncelik: config'teki public-host → oyuncunun bağlanırken
     * yazdığı adres (handshake'teki sanal host) → server.properties'teki server-ip.
     * Proxy (Bungee/Velocity/MCSH) yönlendirme verileri (\0 ve ///) temizlenir.
     */
    private @Nullable Target resolveTarget(Player player, boolean standalone) {
        // Ayrı HTTP sunucusu çalışıyorsa (port veya same-port yedeği) o port kullanılır;
        // Minecraft portu üzerinden sunuluyorsa istemcinin bağlandığı port kullanılır.
        int port = standalone ? settings.port() : 0;

        String raw = settings.publicHost();
        InetSocketAddress virtual = player.getVirtualHost();
        if (raw.isBlank() && virtual != null) {
            raw = virtual.getHostString();
        }
        if (raw.isBlank()) {
            raw = Bukkit.getIp();
        }
        if (raw.isBlank()) {
            return null;
        }

        HostAndPort parsed = parseHostAndPort(raw);
        if (parsed == null) {
            return null;
        }
        String host = parsed.host();
        if (isWildcard(host)) {
            return null;
        }
        // Host, oyuncunun handshake'inde gönderdiği DEĞİŞKEN bir değerdir. Doğrulanmadan
        // URL'e ve log'a girmesi, bozuk/control karakterli adresler üretilmesine yol açardı.
        if (!isSafeHost(host)) {
            String rejected = host;
            logger.fine(() -> "Kaynak paketi adresi için geçersiz host reddedildi: '" + rejected + "'");
            return null;
        }
        if (port == 0) {
            port = parsed.port();
        }
        // same-port modunda dış port, istemcinin handshake'te bildirdiği porttur; panelde
        // iç/dış port farklı olsa bile doğru adres üretilir. (Config'te açıkça yazılan port önceliklidir.)
        if (port == 0 && virtual != null) {
            port = virtual.getPort();
        }
        if (port <= 0) {
            port = Bukkit.getServer().getPort();
        }
        if (port <= 0) {
            port = 25565;
        }
        if (host.indexOf(':') >= 0) {
            host = "[" + host + "]";
        }
        return new Target(host, port);
    }

    private static boolean isWildcard(String host) {
        return "0.0.0.0".equals(host) || "::".equals(host) || "0:0:0:0:0:0:0:0".equals(host);
    }

    /**
     * URL'e gömülecek host'u doğrular: yalnızca IPv4/IPv6 literal veya DNS adı karakterleri.
     * Boşluk, {@code / \ @ ? # % < > " '} ve kontrol karakterleri reddedilir; böylece
     * handshake'ten gelen veri adresin yapısını değiştiremez.
     */
    static boolean isSafeHost(String host) {
        if (host == null) {
            return false;
        }
        String candidate = host.trim();
        if (candidate.isEmpty() || candidate.length() > 253 || !candidate.equals(host)) {
            return false;
        }
        if (candidate.indexOf(':') >= 0) {
            // IPv6 literal: yalnızca hex, iki nokta ve gömülü IPv4 noktalarına izin ver.
            return candidate.matches("[0-9A-Fa-f:.]{2,45}")
                    && candidate.chars().filter(c -> c == ':').count() >= 2
                    && !candidate.contains(":::");
        }
        if (!candidate.matches("[A-Za-z0-9.-]+")) {
            return false;
        }
        for (String label : candidate.split("\\.", -1)) {
            if (label.isEmpty() || label.length() > 63
                    || label.charAt(0) == '-' || label.charAt(label.length() - 1) == '-') {
                return false;
            }
        }
        return true;
    }

    record HostAndPort(String host, int port) {
    }

    /** "oyun.com", "oyun.com:25565", "[::1]:25565", "oyun.com.\0FML\0" gibi girdileri ayıklar. */
    static @Nullable HostAndPort parseHostAndPort(String input) {
        String host = input;

        // Panelden kopyalanan tam adresler de kabul edilsin: "https://oyun.com/yol" → "oyun.com"
        int scheme = host.indexOf("://");
        if (scheme >= 0) {
            host = host.substring(scheme + 3);
        }

        // Proxy temizliği: Bungee/Velocity/MCSH verileri (\0 veya / ile ayrılır).
        int nullIdx = host.indexOf('\0');
        if (nullIdx >= 0) {
            host = host.substring(0, nullIdx);
        }
        int slashIdx = host.indexOf('/');
        if (slashIdx >= 0) {
            host = host.substring(0, slashIdx);
        }

        int port = 0;
        if (host.startsWith("[")) {
            int closeBracket = host.indexOf(']');
            if (closeBracket < 0) {
                return null;
            }
            String literal = host.substring(1, closeBracket);
            if (closeBracket + 1 < host.length() && host.charAt(closeBracket + 1) == ':') {
                port = parsePort(host.substring(closeBracket + 2));
            }
            host = literal;
        } else if (host.indexOf(':') == host.lastIndexOf(':') && host.indexOf(':') > 0) {
            int colon = host.indexOf(':');
            port = parsePort(host.substring(colon + 1));
            host = host.substring(0, colon);
        }

        // DNS FQDN sonundaki noktayı temizle ("test.domain.com." → "test.domain.com")
        while (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        host = host.trim();
        return host.isEmpty() ? null : new HostAndPort(host, port);
    }

    private static int parsePort(String raw) {
        try {
            int value = Integer.parseInt(raw.strip());
            return value > 0 && value <= 65_535 ? value : 0;
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    @Override
    public void close() {
        SamePortPackServer shared;
        HttpServer http;
        // start() (G/Ç thread'i) ile close() (ana thread) aynı anda çalışabilir. Kilit olmadan
        // close() sonrası hâlâ soket açılabilir veya iki kez kapatılabilirdi.
        synchronized (lifecycle) {
            if (closed) {
                return;
            }
            closed = true;
            shared = this.samePort;
            http = this.server;
            this.samePort = null;
            this.server = null;
            this.pack = null;
            this.lastUrls.clear();
        }
        if (shared != null) {
            shared.close();
        }
        if (http != null) {
            http.stop(0);
        }
    }
}

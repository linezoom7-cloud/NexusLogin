package dev.nexus.login.pack;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.CompositeByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import net.kyori.adventure.key.Key;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Kaynak paketini <b>Minecraft'ın kendi portu</b> üzerinden HTTP ile sunar.
 *
 * <p>Paylaşımlı barındırmalarda (Pterodactyl/MCSH vb.) yalnızca sunucunun atanmış portu
 * dışarıya açıktır; 8164 gibi ek portlar NAT/güvenlik duvarı yüzünden erişilemez. Bu sınıf
 * oyuncunun zaten bağlı olduğu porta inen bağlantıları inceler: ilk baytlar bir HTTP isteği
 * ise ZIP'i döndürür, değilse hiçbir şey yapmadan Minecraft'ın normal hattına bırakır.
 *
 * <p>Kanca, Paper'ın sunucu tarafındaki {@code ChannelInitializeListenerHolder} API'sine
 * yansıma ile bağlanır (bu sınıf paper-api'de değil, sunucu JAR'ındadır). API yoksa
 * {@link #install} {@code null} döner ve çağıran ayrı port yöntemine düşer.
 */
final class SamePortPackServer implements AutoCloseable {

    static final String PATH = "/nexus-pack.zip";

    private static final String HANDLER_NAME = "nexus_login_pack_http";
    private static final Key LISTENER_KEY = Key.key("nexus", "pack_http");
    /** Bir HTTP isteğinin başlık bölümü için üst sınır; daha uzunu reddedilir. */
    private static final int MAX_REQUEST_BYTES = 8 * 1024;

    private final Class<?> holderClass;
    /** Güçlü referans: Paper'ın dinleyici listesi proxy'yi zayıf tutmaz, ama biz yine de saklarız. */
    @SuppressWarnings("unused")
    private final Object listener;

    private SamePortPackServer(Class<?> holderClass, Object listener) {
        this.holderClass = holderClass;
        this.listener = listener;
    }

    /**
     * Paper'ın kanal kancasını kurar. <b>Ana thread'den</b> çağrılmalıdır: Paper'ın dinleyici
     * listesi eşzamanlı yazmaya karşı korumalı değildir, bağlantı kabul eden netty thread'leri
     * aynı listeyi okur.
     *
     * @param bytes her istekte güncel ZIP'i döndüren tedarikçi ({@code null} → 404)
     * @return kurulduysa kapatılabilir kanca, aksi halde {@code null}
     */
    static @Nullable SamePortPackServer install(Supplier<byte[]> bytes, Logger logger) {
        if (bytes == null) {
            throw new IllegalArgumentException("bytes");
        }
        try {
            ClassLoader loader = SamePortPackServer.class.getClassLoader();
            Class<?> holder = Class.forName("io.papermc.paper.network.ChannelInitializeListenerHolder", true, loader);
            Class<?> listenerType = Class.forName("io.papermc.paper.network.ChannelInitializeListener", true, loader);

            InvocationHandler handler = (proxy, method, args) -> switch (method.getName()) {
                case "afterInitChannel" -> {
                    if (args != null && args.length == 1 && args[0] instanceof Channel channel) {
                        channel.pipeline().addFirst(HANDLER_NAME, new HttpHandler(bytes));
                    }
                    yield null;
                }
                case "toString" -> "NexusLoginSamePortPack";
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == (args == null ? null : args[0]);
                // API ileride yeni bir metot eklerse eklentiyi kırmayalım.
                default -> null;
            };

            Object listener = Proxy.newProxyInstance(listenerType.getClassLoader(),
                    new Class<?>[]{listenerType}, handler);
            holder.getMethod("addListener", Key.class, listenerType).invoke(null, LISTENER_KEY, listener);
            logger.info("Kaynak paketi Minecraft portu üzerinden sunulacak (ek port gerekmez).");
            return new SamePortPackServer(holder, listener);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
            logger.log(Level.WARNING, "Minecraft portu üzerinden HTTP kancası kurulamadı"
                    + " (Paper sürümü desteklemiyor olabilir).", e);
            return null;
        }
    }

    @Override
    public void close() {
        try {
            holderClass.getMethod("removeListener", Key.class).invoke(null, LISTENER_KEY);
        } catch (ReflectiveOperationException | RuntimeException e) {
            // Kapanışta sorun yalnızca göz ardı edilir; sunucu zaten duruyor.
        }
    }

    /**
     * Tek bir bağlantının ilk baytlarına bakar. HTTP ise ZIP'i yazar, değilse baytları
     * olduğu gibi Minecraft hattına devreder ve kendini hattan çıkarır.
     */
    static final class HttpHandler extends ChannelInboundHandlerAdapter {

        private final Supplier<byte[]> bytes;
        private @Nullable CompositeByteBuf pending;
        private boolean decided;
        private boolean minecraft;
        private boolean served;

        HttpHandler(Supplier<byte[]> bytes) {
            this.bytes = bytes;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (!(msg instanceof ByteBuf buf)) {
                ctx.fireChannelRead(msg);
                return;
            }
            if (minecraft) {
                ctx.fireChannelRead(buf);
                return;
            }
            if (pending == null) {
                pending = ctx.alloc().compositeBuffer();
            }
            pending.addComponent(true, buf);

            if (!decided) {
                if (pending.readableBytes() < 4) {
                    return;
                }
                decided = true;
                if (!looksLikeHttp(pending)) {
                    // Minecraft el sıkışması (ilk bayt paket uzunluğu) veya eski ping (0xFE):
                    // hiçbir baytı değiştirmeden hattın geri kalanına bırak.
                    minecraft = true;
                    ByteBuf buffered = pending;
                    pending = null;
                    ctx.fireChannelRead(buffered);
                    ctx.pipeline().remove(this);
                    return;
                }
            }

            if (served) {
                releasePending();
                return;
            }
            int headerEnd = headerEnd(pending);
            if (headerEnd < 0) {
                if (pending.readableBytes() > MAX_REQUEST_BYTES) {
                    releasePending();
                    ctx.close();
                }
                return;
            }

            served = true;
            try {
                respond(ctx, requestLine(pending, headerEnd));
            } finally {
                releasePending();
            }
        }

        private void respond(ChannelHandlerContext ctx, String requestLine) {
            boolean head = requestLine.startsWith("HEAD ");
            String target = requestTarget(requestLine);
            byte[] body = target != null && target.contains("nexus-pack.zip") ? bytes.get() : null;
            if (body == null) {
                write(ctx, 404, "text/plain; charset=utf-8",
                        "Not found".getBytes(StandardCharsets.UTF_8), false);
                return;
            }
            write(ctx, 200, "application/zip", body, head);
        }

        private static void write(ChannelHandlerContext ctx, int status, String contentType,
                                  byte[] body, boolean headOnly) {
            String reason = status == 200 ? "OK" : "Not Found";
            String header = "HTTP/1.1 " + status + " " + reason + "\r\n"
                    + "Content-Type: " + contentType + "\r\n"
                    + "Content-Length: " + body.length + "\r\n"
                    // Paket SHA-1 ile sürümlenir; içerik değişince URL de değişir.
                    + "Cache-Control: public, max-age=31536000, immutable\r\n"
                    + "Connection: close\r\n"
                    + "\r\n";
            ByteBuf response = ctx.alloc().buffer(header.length() + (headOnly ? 0 : body.length));
            response.writeCharSequence(header, StandardCharsets.US_ASCII);
            if (!headOnly) {
                response.writeBytes(body);
            }
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            releasePending();
            super.channelInactive(ctx);
        }

        @Override
        public void handlerRemoved(ChannelHandlerContext ctx) {
            releasePending();
        }

        private void releasePending() {
            ByteBuf current = pending;
            pending = null;
            if (current != null) {
                current.release();
            }
        }
    }

    // ───────────── test edilebilir saf yardımcılar ─────────────

    /** İlk dört bayt bir HTTP metodu mu? (Minecraft paketleri asla ASCII "GET " ile başlamaz.) */
    static boolean looksLikeHttp(ByteBuf buf) {
        return matches(buf, "GET ") || matches(buf, "HEAD");
    }

    private static boolean matches(ByteBuf buf, String prefix) {
        int start = buf.readerIndex();
        if (buf.readableBytes() < prefix.length()) {
            return false;
        }
        for (int i = 0; i < prefix.length(); i++) {
            if (buf.getByte(start + i) != prefix.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /** Başlık bloğunun bittiği konumu (CRLFCRLF'den hemen sonra) veya {@code -1}. */
    static int headerEnd(ByteBuf buf) {
        int start = buf.readerIndex();
        int end = buf.writerIndex();
        for (int i = start; i + 3 < end; i++) {
            if (buf.getByte(i) == '\r' && buf.getByte(i + 1) == '\n'
                    && buf.getByte(i + 2) == '\r' && buf.getByte(i + 3) == '\n') {
                return i + 4;
            }
        }
        return -1;
    }

    /** İstek satırı (ör. {@code GET /nexus-pack.zip?v=abc HTTP/1.1}). */
    static String requestLine(ByteBuf buf, int headerEnd) {
        int start = buf.readerIndex();
        int limit = Math.min(headerEnd, buf.writerIndex());
        StringBuilder line = new StringBuilder(64);
        for (int i = start; i + 1 < limit; i++) {
            byte b = buf.getByte(i);
            if (b == '\r' && buf.getByte(i + 1) == '\n') {
                break;
            }
            if (line.length() >= 512) {
                break;
            }
            line.append((char) (b & 0xFF));
        }
        return line.toString();
    }

    /** İstek satırındaki hedef (path?query) veya geçersizse {@code null}. */
    static @Nullable String requestTarget(String requestLine) {
        int firstSpace = requestLine.indexOf(' ');
        if (firstSpace < 0) {
            return null;
        }
        int secondSpace = requestLine.indexOf(' ', firstSpace + 1);
        if (secondSpace < 0) {
            return null;
        }
        String target = requestLine.substring(firstSpace + 1, secondSpace).trim();
        return target.isEmpty() ? null : target;
    }
}

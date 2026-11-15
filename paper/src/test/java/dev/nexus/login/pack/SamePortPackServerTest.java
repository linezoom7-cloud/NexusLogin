package dev.nexus.login.pack;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Aynı port üzerinden HTTP sunumunun en riskli kısmı: Minecraft trafiği ile HTTP isteğini
 * ayırt etmek. Bu yardımcılar saf oldukları için doğrudan test edilir.
 */
class SamePortPackServerTest {

    private static ByteBuf ascii(String text) {
        return Unpooled.copiedBuffer(text, StandardCharsets.US_ASCII);
    }

    @Test
    void detectsHttpRequests() {
        assertTrue(SamePortPackServer.looksLikeHttp(ascii("GET /nexus-pack.zip HTTP/1.1\r\n")));
        assertTrue(SamePortPackServer.looksLikeHttp(ascii("HEAD /nexus-pack.zip HTTP/1.1\r\n")));
    }

    @Test
    void doesNotConfuseMinecraftTrafficWithHttp() {
        // El sıkışması: varint uzunluk (0x10) + paket kimliği (0x00)
        ByteBuf handshake = Unpooled.buffer().writeByte(0x10).writeByte(0x00);
        assertFalse(SamePortPackServer.looksLikeHttp(handshake));
        // Eski (1.6 ve öncesi) ping
        ByteBuf legacyPing = Unpooled.buffer().writeByte(0xFE).writeByte(0x01);
        assertFalse(SamePortPackServer.looksLikeHttp(legacyPing));
        handshake.release();
        legacyPing.release();
    }

    @Test
    void findsEndOfHttpHeaders() {
        ByteBuf complete = ascii("GET /x HTTP/1.1\r\nHost: a\r\n\r\n");
        assertEquals(complete.readableBytes(), SamePortPackServer.headerEnd(complete));
        complete.release();

        ByteBuf partial = ascii("GET /x HTTP/1.1\r\nHost: a\r\n");
        assertEquals(-1, SamePortPackServer.headerEnd(partial));
        partial.release();
    }

    @Test
    void parsesRequestLineAndTarget() {
        ByteBuf request = ascii("GET /nexus-pack.zip?v=f62a09ccd390 HTTP/1.1\r\nHost: test41.mcsh.gg\r\n\r\n");
        String line = SamePortPackServer.requestLine(request, SamePortPackServer.headerEnd(request));
        assertEquals("GET /nexus-pack.zip?v=f62a09ccd390 HTTP/1.1", line);
        String target = SamePortPackServer.requestTarget(line);
        assertNotNull(target);
        assertTrue(target.startsWith(SamePortPackServer.PATH));
        request.release();
    }

    @Test
    void rejectsMalformedRequestLine() {
        assertNull(SamePortPackServer.requestTarget("GET"));
        assertNull(SamePortPackServer.requestTarget("GET  "));
    }

    @Test
    void parsesHostAndPort() {
        assertEquals(new PackService.HostAndPort("test41.mcsh.gg", 25565),
                PackService.parseHostAndPort("test41.mcsh.gg:25565"));
        assertEquals(new PackService.HostAndPort("test41.mcsh.gg", 0),
                PackService.parseHostAndPort("test41.mcsh.gg"));
        // Bungee/Velocity ek verisi (\0) temizlenir
        assertEquals(new PackService.HostAndPort("play.example.com", 25565),
                PackService.parseHostAndPort("play.example.com:25565\0FML\0"));
        // IPv6
        assertEquals(new PackService.HostAndPort("2001:db8::1", 25565),
                PackService.parseHostAndPort("[2001:db8::1]:25565"));
        // Sondaki DNS noktası
        assertEquals(new PackService.HostAndPort("example.com", 0),
                PackService.parseHostAndPort("example.com."));
        // Panelden kopyalanmış tam adres
        assertEquals(new PackService.HostAndPort("play.example.com", 25565),
                PackService.parseHostAndPort("https://play.example.com:25565/yol"));
    }

    @Test
    void servesPackZipOverPlainHttp() {
        byte[] payload = {0x50, 0x4B, 0x03, 0x04, 0x00, 0x7F};
        EmbeddedChannel channel = new EmbeddedChannel(
                new SamePortPackServer.HttpHandler(() -> payload));

        // İstek iki TCP parçasına bölünmüş gelse de çalışmalı.
        channel.writeInbound(Unpooled.copiedBuffer("GET /nexus-pa", StandardCharsets.US_ASCII));
        assertNull(channel.readInbound(), "ilk 4 bayttan sonra henüz karar verilmemeli");
        channel.writeInbound(Unpooled.copiedBuffer(
                "ck.zip?v=abc HTTP/1.1\r\nHost: test41.mcsh.gg:25565\r\n\r\n", StandardCharsets.US_ASCII));

        ByteBuf response = channel.readOutbound();
        assertNotNull(response);
        String head = response.toString(0, Math.min(response.readableBytes(), 128), StandardCharsets.US_ASCII);
        assertTrue(head.startsWith("HTTP/1.1 200 OK"), head);
        assertTrue(head.contains("Content-Length: " + payload.length), head);
        assertTrue(head.contains("Content-Type: application/zip"), head);

        byte[] body = new byte[payload.length];
        response.getBytes(response.readableBytes() - payload.length, body);
        assertArrayEquals(payload, body);
        response.release();
        channel.finishAndReleaseAll();
    }

    @Test
    void limitsConcurrentDownloadsAndReleasesSlots() {
        int before = SamePortPackServer.IN_FLIGHT.get();
        SamePortPackServer.IN_FLIGHT.set(SamePortPackServer.MAX_IN_FLIGHT);
        try {
            EmbeddedChannel busy = new EmbeddedChannel(new SamePortPackServer.HttpHandler(() -> new byte[]{1}));
            busy.writeInbound(Unpooled.copiedBuffer(
                    "GET /nexus-pack.zip HTTP/1.1\r\nHost: x\r\n\r\n", StandardCharsets.US_ASCII));
            ByteBuf response = busy.readOutbound();
            assertTrue(response.toString(0, 16, StandardCharsets.US_ASCII).startsWith("HTTP/1.1 503"));
            response.release();
            busy.finishAndReleaseAll();
            assertEquals(SamePortPackServer.MAX_IN_FLIGHT, SamePortPackServer.IN_FLIGHT.get(),
                    "reddedilen istek sayacı artırmamalı");
        } finally {
            SamePortPackServer.IN_FLIGHT.set(before);
        }

        EmbeddedChannel ok = new EmbeddedChannel(new SamePortPackServer.HttpHandler(() -> new byte[]{1}));
        ok.writeInbound(Unpooled.copiedBuffer(
                "GET /nexus-pack.zip HTTP/1.1\r\nHost: x\r\n\r\n", StandardCharsets.US_ASCII));
        ByteBuf served = ok.readOutbound();
        served.release();
        ok.finishAndReleaseAll();
        assertEquals(before, SamePortPackServer.IN_FLIGHT.get(), "bağlantı kapanınca yuva geri verilmeli");
    }

    @Test
    void answers404ForUnknownPaths() {
        EmbeddedChannel channel = new EmbeddedChannel(
                new SamePortPackServer.HttpHandler(() -> new byte[]{1}));
        channel.writeInbound(Unpooled.copiedBuffer(
                "GET /baska-bir-sey HTTP/1.1\r\nHost: x\r\n\r\n", StandardCharsets.US_ASCII));
        ByteBuf response = channel.readOutbound();
        assertNotNull(response);
        String head = response.toString(0, 32, StandardCharsets.US_ASCII);
        assertTrue(head.startsWith("HTTP/1.1 404"), head);
        response.release();
        channel.finishAndReleaseAll();
    }

    @Test
    void passesMinecraftBytesThroughUntouched() {
        SamePortPackServer.HttpHandler handler = new SamePortPackServer.HttpHandler(() -> new byte[]{1});
        EmbeddedChannel channel = new EmbeddedChannel(handler);
        byte[] handshake = {0x10, 0x00, (byte) 0xFF, 0x05, 0x09, 0x6C, 0x6F, 0x63, 0x61, 0x6C};
        channel.writeInbound(Unpooled.copiedBuffer(handshake));

        ByteBuf forwarded = channel.readInbound();
        assertNotNull(forwarded, "Minecraft trafiği hattın geri kalanına iletilmeli");
        byte[] actual = new byte[forwarded.readableBytes()];
        forwarded.readBytes(actual);
        assertArrayEquals(handshake, actual);
        forwarded.release();
        assertNull(channel.pipeline().context(handler),
                "karar verildikten sonra handler hattan çıkmalı");
        channel.finishAndReleaseAll();
    }

    @Test
    void forwardsLegacyPingWithoutWaitingForFourBytes() {
        SamePortPackServer.HttpHandler handler = new SamePortPackServer.HttpHandler(() -> new byte[]{1});
        EmbeddedChannel channel = new EmbeddedChannel(handler);
        byte[] legacyPing = {(byte) 0xFE, 0x01};
        channel.writeInbound(Unpooled.copiedBuffer(legacyPing));

        ByteBuf forwarded = channel.readInbound();
        assertNotNull(forwarded, "Eski (legacy) 2-baytlık ping hemen iletilmeli");
        byte[] actual = new byte[forwarded.readableBytes()];
        forwarded.readBytes(actual);
        assertArrayEquals(legacyPing, actual);
        forwarded.release();
        assertNull(channel.pipeline().context(handler),
                "Legacy ping sonrası handler hattan çıkmalı");
        channel.finishAndReleaseAll();
    }

    @Test
    void buffersIncompleteHttpWithoutCrashing() {
        EmbeddedChannel channel = new EmbeddedChannel(
                new SamePortPackServer.HttpHandler(() -> new byte[]{1}));
        // Tek bayt (örneğin yavaş/kısmi okuma) geldiğinde ne hata vermeli ne de erken davranmalı
        channel.writeInbound(Unpooled.copiedBuffer("G", StandardCharsets.US_ASCII));
        assertNull(channel.readInbound());
        assertNull(channel.readOutbound());
        channel.close();
    }
}

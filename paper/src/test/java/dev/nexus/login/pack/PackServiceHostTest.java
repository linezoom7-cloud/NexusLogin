package dev.nexus.login.pack;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Kaynak paketi URL'sine gömülen host, oyuncunun handshake'inden gelen değişken bir
 * değerdir. Bu testler URL/log enjeksiyonunu ve geçersiz port ayrıştırmasını kapsar.
 */
class PackServiceHostTest {

    @Test
    void acceptsOrdinaryHosts() {
        assertTrue(PackService.isSafeHost("oyun.example.com"));
        assertTrue(PackService.isSafeHost("localhost"));
        assertTrue(PackService.isSafeHost("127.0.0.1"));
        assertTrue(PackService.isSafeHost("255.255.255.255"));
        assertTrue(PackService.isSafeHost("::1"));
        assertTrue(PackService.isSafeHost("2001:db8::1"));
        assertTrue(PackService.isSafeHost("::ffff:192.168.0.1"));
    }

    @Test
    void rejectsUrlAndLogInjectionCharacters() {
        for (String candidate : new String[]{
                "", " ", " oyun.com", "oyun.com ", "oyun.com/path", "oyun.com\\x",
                "oyun.com?x=1", "oyun.com#frag", "oyun.com@evil", "oyun.com%00",
                "oyun.com\0FML", "oyun.com\r\nX: y", "oyun.com<script>", "a:b",
                "https://oyun.com"}) {
            assertFalse(PackService.isSafeHost(candidate), "reddedilmeli: " + candidate);
        }
        assertFalse(PackService.isSafeHost(null));
    }

    @Test
    void rejectsMalformedDnsLabels() {
        for (String candidate : new String[]{
                "-oyun.com", "oyun-.com", "a..b", ".", "oyun..com", "a".repeat(64) + ".com",
                "a".repeat(254)}) {
            assertFalse(PackService.isSafeHost(candidate), "reddedilmeli: " + candidate);
        }
    }

    @Test
    void parsesHostAndOptionalPort() {
        PackService.HostAndPort plain = PackService.parseHostAndPort("oyun.example.com");
        assertNotNull(plain);
        assertEquals("oyun.example.com", plain.host());
        assertEquals(0, plain.port(), "port yoksa 0 dönmeli");

        PackService.HostAndPort withPort = PackService.parseHostAndPort("oyun.example.com:25570");
        assertNotNull(withPort);
        assertEquals("oyun.example.com", withPort.host());
        assertEquals(25570, withPort.port());

        PackService.HostAndPort bracketed = PackService.parseHostAndPort("[2001:db8::1]:25565");
        assertNotNull(bracketed);
        assertEquals("2001:db8::1", bracketed.host());
        assertEquals(25565, bracketed.port());
    }

    @Test
    void stripsProxyAndUrlNoise() {
        PackService.HostAndPort handshake = PackService.parseHostAndPort("oyun.example.com\0FML\0");
        assertNotNull(handshake);
        assertEquals("oyun.example.com", handshake.host());

        PackService.HostAndPort url = PackService.parseHostAndPort("https://oyun.example.com/panel");
        assertNotNull(url);
        assertEquals("oyun.example.com", url.host());

        PackService.HostAndPort fqdn = PackService.parseHostAndPort("test.domain.com.");
        assertNotNull(fqdn);
        assertEquals("test.domain.com", fqdn.host(), "sondaki DNS noktası temizlenmeli");
    }

    @Test
    void invalidPortsFallBackToZero() {
        for (String input : new String[]{"oyun.com:0", "oyun.com:-5", "oyun.com:65536",
                "oyun.com:abc", "oyun.com:"}) {
            PackService.HostAndPort parsed = PackService.parseHostAndPort(input);
            assertNotNull(parsed, input);
            assertEquals("oyun.com", parsed.host(), input);
            assertEquals(0, parsed.port(), "geçersiz port 0 olmalı: " + input);
        }
    }

    @Test
    void unclosedIpv6BracketIsRejected() {
        assertNull(PackService.parseHostAndPort("[2001:db8::1"));
        assertNull(PackService.parseHostAndPort("   "));
    }
}

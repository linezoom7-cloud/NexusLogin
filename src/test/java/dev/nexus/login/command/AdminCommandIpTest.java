package dev.nexus.login.command;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@code /nexus unlockip} yalnızca gerçek bir IPv4/IPv6 adresi kabul etmeli. Hostname kabul
 * edilirse yönetici yanlışlıkla başka bir IP'nin cezasını silebilir veya DNS sorgusu tetiklenir.
 */
class AdminCommandIpTest {

    @Test
    void normalizesValidIpv4() {
        assertEquals("192.168.1.10", AdminCommand.normalizeIpLiteral("192.168.1.10"));
        assertEquals("8.8.8.8", AdminCommand.normalizeIpLiteral("  8.8.8.8  "));
        assertEquals("0.0.0.0", AdminCommand.normalizeIpLiteral("0.0.0.0"));
        assertEquals("255.255.255.255", AdminCommand.normalizeIpLiteral("255.255.255.255"));
    }

    @Test
    void rejectsInvalidIpv4WithoutDnsLookup() {
        for (String candidate : new String[]{
                "999.999.999.999", "1.2.3", "1.2.3.4.5", "1.2.3.256",
                "01.2.3.4", "1.02.3.4", "1.2.3.004", "1.2.3.-1",
                "localhost", "example.com", "1.2.3.4.example.com", ""}) {
            assertNull(AdminCommand.normalizeIpLiteral(candidate), "reddedilmeli: " + candidate);
        }
        assertNull(AdminCommand.normalizeIpLiteral(null));
    }

    @Test
    void normalizesValidIpv6() {
        String loopback = AdminCommand.normalizeIpLiteral("::1");
        assertNotNull(loopback);
        assertTrue(loopback.contains(":"), "IPv6 adresi dönmeli: " + loopback);

        String full = AdminCommand.normalizeIpLiteral("2001:0db8:0000:0000:0000:0000:0000:0001");
        assertEquals("2001:db8:0:0:0:0:0:1", full);
    }

    @Test
    void rejectsHostnamesAndMalformedIpv6() {
        assertNull(AdminCommand.normalizeIpLiteral("example.com"));
        assertNull(AdminCommand.normalizeIpLiteral("::::"));
        assertNull(AdminCommand.normalizeIpLiteral("2001:db8::1::2"));
        assertNull(AdminCommand.normalizeIpLiteral("gggg::1"));
    }
}

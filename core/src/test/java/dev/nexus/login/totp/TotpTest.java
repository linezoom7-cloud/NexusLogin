package dev.nexus.login.totp;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TotpTest {

    private static final byte[] RFC_SECRET = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

    @Test
    void rfc6238AppendixBSha1VectorsWith8And6Digits() {
        long[] times = {59L, 1111111109L, 1111111111L, 1234567890L, 2000000000L, 20000000000L};
        int[] eight = {94287082, 7081804, 14050471, 89005924, 69279037, 65353130};
        for (int i = 0; i < times.length; i++) {
            long step = Totp.step(times[i]);
            assertEquals(eight[i], Totp.hotp(RFC_SECRET, step, 8), "8 hane t=" + times[i]);
            assertEquals(eight[i] % 1_000_000, Totp.hotp(RFC_SECRET, step, 6), "6 hane t=" + times[i]);
        }
    }

    @Test
    void matchAcceptsPlusMinusOneStepAndRejectsTwo() {
        long t = 1_700_000_000L;
        long now = Totp.step(t);
        for (long d = -1; d <= 1; d++) {
            String code = String.format("%06d", Totp.hotp(RFC_SECRET, now + d, 6));
            assertEquals(now + d, Totp.match(RFC_SECRET, code, t), "adım farkı " + d);
        }
        for (long d : new long[]{-2, 2}) {
            String code = String.format("%06d", Totp.hotp(RFC_SECRET, now + d, 6));
            long m = Totp.match(RFC_SECRET, code, t);
            assertTrue(m == -1 || Math.abs(m - now) <= 1, "±2 adım kabul edilmemeli (çakışma dışında)");
        }
    }

    @Test
    void matchRejectsMalformedCodes() {
        long t = 1_700_000_000L;
        for (String bad : new String[]{null, "", "12345", "1234567", "12a456", " 12345", "١٢٣٤٥٦"}) {
            assertEquals(-1, Totp.match(RFC_SECRET, bad, t), "kod: " + bad);
        }
    }

    @Test
    void base32Rfc4648VectorsAndTolerantDecode() {
        String[][] v = {{"", ""}, {"f", "MY"}, {"fo", "MZXQ"}, {"foo", "MZXW6"}, {"foob", "MZXW6YQ"},
                {"fooba", "MZXW6YTB"}, {"foobar", "MZXW6YTBOI"}};
        for (String[] pair : v) {
            assertEquals(pair[1], Base32.encode(pair[0].getBytes(StandardCharsets.US_ASCII)));
            assertEquals(pair[0], new String(Base32.decode(pair[1]), StandardCharsets.US_ASCII));
        }
        assertEquals("foobar", new String(Base32.decode("mzxw 6ytb-oi======"), StandardCharsets.US_ASCII));
        assertThrows(IllegalArgumentException.class, () -> Base32.decode("MZX1"));
        byte[] secret = Totp.newSecret(new SecureRandom());
        assertEquals(32, Base32.encode(secret).length(), "20 bayt = 32 Base32 karakter");
        assertArrayEquals(secret, Base32.decode(Base32.encode(secret)));
    }

    @Test
    void otpauthUriIsEncoded() {
        String uri = Totp.otpauthUri("Nexus Login", "Steve", "JBSWY3DPEHPK3PXP");
        assertEquals("otpauth://totp/Nexus%20Login:Steve?secret=JBSWY3DPEHPK3PXP&issuer=Nexus%20Login"
                + "&algorithm=SHA1&digits=6&period=30", uri);
    }

    @Test
    void vaultRoundTripTamperAadAndWrongKey() throws Exception {
        SecureRandom random = new SecureRandom();
        byte[] key = new byte[32];
        random.nextBytes(key);
        TotpVault vault = new TotpVault(key, random);
        UUID owner = UUID.randomUUID();
        byte[] secret = Totp.newSecret(random);

        String a = vault.encrypt(owner, secret);
        String b = vault.encrypt(owner, secret);
        assertNotEquals(a, b, "her şifrelemede yeni nonce");
        assertArrayEquals(secret, vault.decrypt(owner, a));

        assertThrows(TotpVault.VaultException.class, () -> vault.decrypt(UUID.randomUUID(), a), "başka satıra kopya");
        byte[] other = new byte[32];
        random.nextBytes(other);
        assertThrows(TotpVault.VaultException.class, () -> new TotpVault(other, random).decrypt(owner, a), "yanlış anahtar");
        byte[] raw = java.util.Base64.getDecoder().decode(a.substring(3));
        for (int i = 0; i < raw.length; i++) {
            byte[] copy = raw.clone();
            copy[i] ^= 1;
            String tampered = "v1$" + java.util.Base64.getEncoder().encodeToString(copy);
            assertThrows(TotpVault.VaultException.class, () -> vault.decrypt(owner, tampered), "bayt " + i);
        }
        assertThrows(TotpVault.VaultException.class, () -> vault.decrypt(owner, "v2$xxx"));
        assertThrows(TotpVault.VaultException.class, () -> vault.decrypt(owner, "v1$!!!"));
        assertThrows(TotpVault.VaultException.class, () -> vault.decrypt(owner, "v1$AAAA"));
        assertThrows(IllegalArgumentException.class, () -> new TotpVault(new byte[16], random));
    }
}

package dev.nexus.login.totp;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * RFC 6238 TOTP: HMAC-SHA1, 6 hane, 30 sn adım, T0=0 (Google Authenticator ve benzerleriyle uyumlu sabitler).
 * Doğrulama ±{@link #WINDOW} adım kabul eder; tüm adaylar erken çıkışsız karşılaştırılır.
 */
public final class Totp {

    public static final int DIGITS = 6;
    public static final long STEP_SECONDS = 30;
    public static final int WINDOW = 1;
    public static final int SECRET_BYTES = 20;

    private static final int[] POW10 = {1, 10, 100, 1_000, 10_000, 100_000, 1_000_000, 10_000_000, 100_000_000};

    private Totp() {
    }

    public static byte[] newSecret(SecureRandom random) {
        byte[] secret = new byte[SECRET_BYTES];
        random.nextBytes(secret);
        return secret;
    }

    /** RFC 4226 HOTP. */
    public static int hotp(byte[] key, long counter, int digits) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(key, "HmacSHA1"));
            byte[] h = mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array());
            int offset = h[h.length - 1] & 0x0F;
            int binary = ((h[offset] & 0x7F) << 24) | ((h[offset + 1] & 0xFF) << 16)
                    | ((h[offset + 2] & 0xFF) << 8) | (h[offset + 3] & 0xFF);
            return binary % POW10[digits];
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA1 kullanılamıyor", e);
        }
    }

    public static long step(long epochSeconds) {
        return Math.floorDiv(epochSeconds, STEP_SECONDS);
    }

    /**
     * @return kodun eşleştiği adım; eşleşme yoksa -1. Çağıran, dönen adımın son kabul edilenden büyük
     *         olduğunu (tekrar kullanım) ayrıca denetlemelidir.
     */
    public static long match(byte[] secret, String code, long epochSeconds) {
        if (code == null || code.length() != DIGITS || !code.chars().allMatch(ch -> ch >= '0' && ch <= '9')) {
            return -1;
        }
        byte[] given = code.getBytes(StandardCharsets.US_ASCII);
        long now = step(epochSeconds);
        long matched = -1;
        for (long s = now - WINDOW; s <= now + WINDOW; s++) {
            byte[] expected = String.format("%0" + DIGITS + "d", hotp(secret, s, DIGITS)).getBytes(StandardCharsets.US_ASCII);
            if (MessageDigest.isEqual(expected, given) && matched < 0) {
                matched = s; // erken çıkış yok: her aday hesaplanır
            }
        }
        return matched;
    }

    /** Doğrulayıcı uygulamalar için {@code otpauth://} adresi (QR yerine elle giriş de mümkün). */
    public static String otpauthUri(String issuer, String account, String base32Secret) {
        String label = enc(issuer) + ":" + enc(account);
        return "otpauth://totp/" + label + "?secret=" + base32Secret + "&issuer=" + enc(issuer)
                + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + STEP_SECONDS;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }
}

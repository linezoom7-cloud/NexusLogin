package dev.nexus.login.crypto;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

/** RFC 5869 HKDF-Expand (HMAC-SHA256). Girdi anahtarı zaten düzgün rastgele olduğunda Extract atlanır (§3.3). */
public final class Hkdf {

    private Hkdf() {
    }

    public static byte[] expand(byte[] prk, String info, int length) {
        return expand(prk, info.getBytes(StandardCharsets.US_ASCII), length);
    }

    public static byte[] expand(byte[] prk, byte[] info, int length) {
        if (length < 1 || length > 255 * 32) {
            throw new IllegalArgumentException("HKDF uzunluğu geçersiz: " + length);
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(prk, "HmacSHA256"));
            byte[] out = new byte[length];
            byte[] previous = new byte[0];
            int offset = 0;
            for (int counter = 1; offset < length; counter++) {
                mac.update(previous);
                mac.update(info);
                mac.update((byte) counter);
                previous = mac.doFinal();
                int n = Math.min(previous.length, length - offset);
                System.arraycopy(previous, 0, out, offset, n);
                offset += n;
            }
            return out;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 kullanılamıyor", e);
        }
    }
}

package dev.nexus.login.totp;

import dev.nexus.login.crypto.Hkdf;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

/**
 * TOTP secret'larının AES-256-GCM ile saklanması. Anahtar veritabanı dışındadır; yalnız DB sızarsa
 * secret'lar çözülemez. AAD = oyuncu UUID'si + biçim sürümü: şifreli değer başka bir satıra
 * kopyalanırsa çözülmez. Biçim: {@code v1$base64(nonce12 || ciphertext || tag16)}.
 */
public final class TotpVault {

    public static final int KEY_BYTES = 32;
    private static final String PREFIX = "v1$";
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec aesKey;
    private final SecureRandom random;

    public TotpVault(byte[] masterKey, SecureRandom random) {
        if (masterKey.length < KEY_BYTES) {
            throw new IllegalArgumentException("TOTP anahtarı en az " + KEY_BYTES + " bayt olmalı");
        }
        this.aesKey = new SecretKeySpec(Hkdf.expand(masterKey, "nexuslogin/totp-aes/v1", KEY_BYTES), "AES");
        this.random = random;
    }

    /** Bozuk, kurcalanmış veya başka anahtarla şifrelenmiş değer. */
    public static final class VaultException extends Exception {
        VaultException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public String encrypt(UUID owner, byte[] secret) {
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad(owner));
            byte[] sealed = cipher.doFinal(secret);
            byte[] blob = ByteBuffer.allocate(NONCE_BYTES + sealed.length).put(nonce).put(sealed).array();
            return PREFIX + Base64.getEncoder().encodeToString(blob);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM kullanılamıyor", e);
        }
    }

    public byte[] decrypt(UUID owner, String stored) throws VaultException {
        if (stored == null || !stored.startsWith(PREFIX)) {
            throw new VaultException("bilinmeyen biçim", null);
        }
        byte[] blob;
        try {
            blob = Base64.getDecoder().decode(stored.substring(PREFIX.length()));
        } catch (IllegalArgumentException e) {
            throw new VaultException("bozuk base64", e);
        }
        if (blob.length < NONCE_BYTES + TAG_BITS / 8) {
            throw new VaultException("çok kısa", null);
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(TAG_BITS, blob, 0, NONCE_BYTES));
            cipher.updateAAD(aad(owner));
            return cipher.doFinal(blob, NONCE_BYTES, blob.length - NONCE_BYTES);
        } catch (AEADBadTagException e) {
            throw new VaultException("doğrulama etiketi tutmadı (yanlış anahtar, kurcalama veya başka satır)", e);
        } catch (GeneralSecurityException e) {
            throw new VaultException("çözülemedi", e);
        }
    }

    private static byte[] aad(UUID owner) {
        return ByteBuffer.allocate(16 + 2).putLong(owner.getMostSignificantBits())
                .putLong(owner.getLeastSignificantBits()).put("v1".getBytes(StandardCharsets.US_ASCII)).array();
    }
}


package dev.jefe.mailsorter.infra.crypto;

import dev.jefe.mailsorter.application.exception.CryptoException;
import dev.jefe.mailsorter.application.port.out.SecretCipher;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

public final class AesGcmSecretCipher implements SecretCipher {
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private final SecretKey key;
    private final SecureRandom random = new SecureRandom();

    public AesGcmSecretCipher(byte[] keyBytes) {
        if (keyBytes.length != 32) {
            throw new IllegalArgumentException("AES-256 key must be 32 bytes");
        }
        this.key = new SecretKeySpec(keyBytes, "AES");
    }

    @Override
    public String encrypt(String plain) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);                                  // fresh IV on every call

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));

            byte[] out = ByteBuffer.allocate(IV_BYTES + ciphertext.length)
                    .put(iv)
                    .put(ciphertext)
                    .array();
            return Base64.getEncoder().encodeToString(out);       // Base64(iv || ciphertext+tag)
        } catch (GeneralSecurityException e) {
            throw new CryptoException("Could not encrypt secret", e);
        }
    }

    @Override
    public String decrypt(String stored) {
        try {
            byte[] all = Base64.getDecoder().decode(stored);
            if (all.length <= IV_BYTES) {
                throw new CryptoException("Stored secret is malformed", null);
            }

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, all, 0, IV_BYTES));
            byte[] plain = cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            // wrong key, tampered data (AEADBadTagException) or invalid Base64
            throw new CryptoException("Could not decrypt secret (wrong key or corrupted data)", e);
        }
    }
}

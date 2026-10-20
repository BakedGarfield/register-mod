package io.github.bakedgarfield.registermod;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.GeneralSecurityException;
import java.util.Arrays;
import java.util.Base64;

/** PINs are never stored as plaintext; note that four digits remain inherently weak. */
public final class PinCrypto {
    private static final int ITERATIONS = 210_000;
    private static final SecureRandom RANDOM = new SecureRandom();

    private PinCrypto() { }

    public static boolean valid(String input) {
        return input != null && input.matches("[0-9]{4}");
    }

    public static String newSalt() {
        byte[] salt = new byte[16];
        RANDOM.nextBytes(salt);
        return Base64.getEncoder().encodeToString(salt);
    }

    public static String hash(String pin, String saltBase64) throws GeneralSecurityException {
        char[] characters = pin.toCharArray();
        byte[] salt = Base64.getDecoder().decode(saltBase64);
        PBEKeySpec key = new PBEKeySpec(characters, salt, ITERATIONS, 256);
        try {
            byte[] hashed = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(key).getEncoded();
            return Base64.getEncoder().encodeToString(hashed);
        } finally {
            key.clearPassword();
            Arrays.fill(characters, '\0');
            Arrays.fill(salt, (byte) 0);
        }
    }

    public static boolean matches(String suppliedPin, String salt, String expectedHash)
            throws GeneralSecurityException {
        byte[] expected = Base64.getDecoder().decode(expectedHash);
        byte[] actual = Base64.getDecoder().decode(hash(suppliedPin, salt));
        boolean ok = MessageDigest.isEqual(actual, expected);
        Arrays.fill(actual, (byte) 0);
        Arrays.fill(expected, (byte) 0);
        return ok;
    }
}

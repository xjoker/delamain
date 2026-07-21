package com.acme.demo;

/** Holds a unique string constant (code-search probe) and a native method. */
public class CryptoUtils {

    // Unique top-level string constant -> code-search / trigram probe.
    public static final String SECRET_MARKER = "ACME_SECRET_TOKEN_9F3A";

    // Native method -> search_native_methods / Frida native-hook probe.
    public native byte[] encryptNative(byte[] data);

    byte[] deriveKey(byte[] seed) {
        byte[] out = new byte[seed.length];
        for (int i = 0; i < seed.length; i++) {
            out[i] = (byte) (seed[i] ^ 0x5A);
        }
        return out;
    }
}

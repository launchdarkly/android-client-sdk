package com.launchdarkly.sdk.android;

import android.util.Base64;

import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Provides a single hash method that takes a String and returns a unique filename-safe hash of it.
 * It exists as a separate class so we can unit test it and assert that different instances
 * produce the same output given the same input.
 */
class ContextHasher {

    String hash(String toHash) {
        try {
            MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
            messageDigest.reset();
            // All instances of the JVM are required to support UTF-8 charset
            byte[] hash = messageDigest.digest(toHash.getBytes(Charset.forName("UTF-8")));
            return Base64.encodeToString(hash, Base64.URL_SAFE + Base64.NO_WRAP);
        } catch (NoSuchAlgorithmException ignored) {
            // SHA-256 should be supported on all devices. This exception case is because Java
            // can't statically verify that the string "SHA-256" is always a valid MessageDigest.
            // We return a string of the correct length in case anything depends on it.
            return "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
        }
    }
}

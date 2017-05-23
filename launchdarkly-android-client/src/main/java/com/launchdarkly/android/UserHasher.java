package com.launchdarkly.android;


import android.util.Base64;

import com.google.common.base.Charsets;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hashing;


/**
 * Provides a single hash method that takes a String and returns a unique filename-safe hash of it.
 * It exists as a separate class so we can unit test it and assert that different instances
 * produce the same output given the same input.
 */
class UserHasher {
    private final HashFunction HashFunction = Hashing.md5();

    String hash(String toHash) {
        byte[] hash = HashFunction
                .newHasher()
                .putString(toHash, Charsets.UTF_8)
                .hash()
                .asBytes();

        return Base64.encodeToString(hash, Base64.URL_SAFE + Base64.NO_WRAP);
    }
}

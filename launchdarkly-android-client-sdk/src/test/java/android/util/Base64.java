package android.util;

// This file exists only to support the unit tests in src/test/java. The issue is that the SDK
// code uses android.util.Base64, which only exists in the Android runtime library; but the unit
// tests (as opposed to the instrumented tests in src/androidTest/java) run against the regular
// Java runtime library. The solution is to put an android.util.Base64 class in the classpath that
// simply delegates to java.util.Base64.
//
// We can't simply change the SDK code to use java.util.Base64 because that is only available in
// Android API 26 and above.

public class Base64 {
    public static String encodeToString(byte[] input, int flags) {
        return java.util.Base64.getEncoder().encodeToString(input);
    }

    public static byte[] decode(String str, int flags) {
        return java.util.Base64.getDecoder().decode(str);
    }
}

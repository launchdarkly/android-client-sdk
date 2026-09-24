package android.os;

// This file exists only to support the unit tests in src/test/java. The issue is that the SDK
// code uses android.os.Process, which only exists in the Android runtime library; but the unit
// tests (as opposed to the instrumented tests in src/androidTest/java) run against the regular
// Java runtime library. Without this class the mockable android.jar is used instead, and every
// method on it throws "Method ... not mocked".
//
// StreamingDataSource.stop() lowers the priority of its shutdown thread before closing the
// EventSource. With the mockable android.jar that call throws, the shutdown thread dies before
// closing anything, and stop() silently becomes a no-op in unit tests. Thread priority has no
// meaningful equivalent off-device, so the stub just does nothing and lets shutdown proceed.

public class Process {
    public static final int THREAD_PRIORITY_BACKGROUND = 10;

    public static void setThreadPriority(int priority) {
    }
}

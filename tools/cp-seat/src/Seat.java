import java.security.Security;
import java.security.Provider;

/**
 * CP/stdio seat bootstrap: boots Robolectric, then runs the seat forever.
 * JVM-side driver for the LaunchDarkly Android client SDK conformance seat.
 *
 * CP-SPEC 5.1: stdout carries ONLY CP envelope lines — this class prints nothing
 * to stdout; bootstrap and runner chatter go to stderr only.
 */
public class Seat {
  public static void main(String[] args) throws Exception {
    System.setProperty("secpkg", "unused");
    Security.addProvider(new Provider(
        "Conscrypt", "1.1-stub", "stub provider (no aarch64 native on this host)") {});
    System.err.println("SEAT: stub Conscrypt provider registered");
    if (org.robolectric.shadows.ShadowLog.stream == null) {
      org.robolectric.shadows.ShadowLog.stream = System.err;  // android.util.Log must NOT touch stdout
    }
    Class<?> testClass = Class.forName(args.length > 0 ? args[0] : "SeatRs");
    org.robolectric.RobolectricTestRunner runner = new org.robolectric.RobolectricTestRunner(testClass);
    org.junit.runner.JUnitCore core = new org.junit.runner.JUnitCore();
    org.junit.runner.Result r = core.run(runner);
    System.err.println("SEAT runner finished: ran=" + r.getRunCount() + " failed=" + r.getFailureCount());
    if (r.wasSuccessful()) System.exit(0);
    for (org.junit.runner.notification.Failure f : r.getFailures()) {
      System.err.println("SEAT failure: " + f.getMessage());
    }
    System.exit(1);
  }
}

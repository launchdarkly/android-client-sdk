import java.security.Security;
import java.security.Provider;

/**
 * CP/stdio seat bootstrap: boots Robolectric, then runs SeatServeMain.serve() forever.
 * JVM-side driver for the LaunchDarkly Android client SDK conformance seat.
 */
public class Seat {
  public static void main(String[] args) throws Exception {
    Security.addProvider(new Provider(
        "Conscrypt", "1.1-stub", "stub provider (no aarch64 native on this host)") {});
    System.out.println("SEAT: stub Conscrypt provider registered");
    String[] junitArgs = args.length > 0 ? args : new String[]{"SeatSmoke"};
    org.junit.runner.JUnitCore.main(junitArgs);
  }
}

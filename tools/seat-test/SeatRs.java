import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

/**
 * Robolectric-hosted boot for the CP seat.
 *
 * Robolectric's paused main looper accepts idle() only from the thread that owns it —
 * the JUnit test thread. So this test thread IS the seat pump: it polls stdin non-blocking,
 * dispatches each envelope to com.launchdarkly.seat.SeatServe's internal worker queue,
 * and drains the shadow main looper between reads.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, manifest = Config.NONE, application = android.app.Application.class)
public class SeatRs {
  @Test
  public void hostSeatForever() throws Exception {
    System.err.println("SEAT-RS: app booted, serving CP/stdio on stdin");
    com.launchdarkly.seat.SeatServe.servePumping(RuntimeEnvironment.getApplication(),
        () -> {
          try {
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
          } catch (Throwable t) {
            // drain failure: record and continue; a stuck looper surfaces as action timeouts
            System.err.println("[seat-pump] idle() failed: " + t);
          }
        });
  }
}

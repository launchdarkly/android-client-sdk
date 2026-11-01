package com.launchdarkly.sdk.android;

import android.app.Application;

/**
 * CENTINEL cp-seat's Application stub (the platform-state face-mint law):
 * the seat's Robolectric boot installs this Application so the SDK's own
 * AndroidPlatformState test hand-shakes price the seat's platform faces —
 * foreground TRUE at init (TestApplicationForegroundStateOverride, set once
 * in AndroidPlatformState's constructor) and the network-available face TRUE
 * (TestNetworkOverride, honored on every isNetworkAvailable() call). Both
 * interfaces are package-private inside com.launchdarkly.sdk.android's
 * AndroidPlatformState, so the stub compiles as a same-package sibling
 * (seat-classes rides ahead of sdk.jar in the seat-serve.sh classpath, so
 * the seat's seams always win). Module-internal seat bytes, no SDK surface
 * growth — the C-FR-OFFLINE-TOGGLE-PROBE/02 face grades the SDK's own
 * modeResolutionTable law once the JVM seat stops pricing the never-resumed
 * activity state.
 */
public final class SeatStubs {
  private SeatStubs() {}

  public static class SeatApplication extends Application
      implements AndroidPlatformState.TestApplicationForegroundStateOverride,
                 AndroidPlatformState.TestNetworkOverride {
    @Override
    public boolean isTestFixtureInitiallyInForeground() {
      return true;
    }

    @Override
    public boolean isTestFixtureNetworkAvailable() {
      return true;
    }
  }
}

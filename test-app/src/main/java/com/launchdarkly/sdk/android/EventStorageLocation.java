package com.launchdarkly.sdk.android;

import java.io.File;

/**
 * Where the SDK keeps events for one mobile key, for the test app's event storage view.
 * <p>
 * In the SDK's package because {@link EventDirectory} is package-private there, so the view asks the SDK
 * for the path rather than working out the hash a second time. The app and the library share one class
 * loader, so that access holds at run time.
 */
public final class EventStorageLocation {
    private EventStorageLocation() {
    }

    public static File directory(File noBackupFilesDir, String mobileKey) {
        return EventDirectory.defaultDirectory(noBackupFilesDir, mobileKey);
    }
}

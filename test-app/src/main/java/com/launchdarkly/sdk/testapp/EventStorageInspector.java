package com.launchdarkly.sdk.testapp;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.format.Formatter;
import android.widget.TextView;

import com.launchdarkly.sdk.android.EventStorageLocation;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * What the SDK has left in this app's event directory: the logs live processes append to and the batches
 * waiting to be delivered, shown in a text view and read again every second while it is watched.
 * <p>
 * Read from the file names, sizes and modification times alone, and never opened, so looking cannot change
 * what it looks at: a lock taken to see whether a log is held would stop another process's recovery from
 * claiming it.
 * <p>
 * Anything that comes and goes between two reads is never shown, such as a {@code new-} log, which is
 * renamed as soon as it has its header, or a batch delivered within a second of being closed.
 */
final class EventStorageInspector {
    private static final long INTERVAL_MILLIS = 1_000;
    /** Enough to see a few processes' logs and a few batches without pushing the rest of the screen off it. */
    private static final int MAX_ENTRIES_SHOWN = 6;

    private static final String LOG_PREFIX = "pending-";
    private static final String OPENING_PREFIX = "new-";
    private static final String BATCH_PREFIX = "ready-";
    /** The version of how the rest of a batch's name reads; the count follows the payload ID. */
    private static final String OWN_BATCH_PREFIX = BATCH_PREFIX + "v1-";

    private final Context context;
    private final TextView view;
    private final File directory;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refresh();
            main.postDelayed(this, INTERVAL_MILLIS);
        }
    };
    private boolean watching;

    EventStorageInspector(Context context, TextView view, String mobileKey) {
        this.context = context;
        this.view = view;
        this.directory = EventStorageLocation.directory(context.getNoBackupFilesDir(), mobileKey);
    }

    File directory() {
        return directory;
    }

    void startWatching() {
        if (watching) {
            return;
        }
        watching = true;
        tick.run();
    }

    void stopWatching() {
        watching = false;
        main.removeCallbacks(tick);
    }

    private void refresh() {
        File[] files = directory.listFiles(file -> !file.getName().startsWith("."));
        if (files == null) {
            view.setText("Event storage: nothing stored yet");
            return;
        }
        Arrays.sort(files, (a, b) -> {
            int byTime = Long.compare(a.lastModified(), b.lastModified());
            return byTime != 0 ? byTime : a.getName().compareTo(b.getName());
        });

        int logs = 0;
        int batches = 0;
        int events = 0;
        List<String> lines = new ArrayList<>();
        SimpleDateFormat time = new SimpleDateFormat("HH:mm:ss", Locale.US);
        for (File file : files) {
            String name = file.getName();
            int count = eventsInBatchNamed(name);
            if (name.startsWith(LOG_PREFIX)) {
                logs++;
            } else if (name.startsWith(BATCH_PREFIX)) {
                batches++;
                events += Math.max(count, 0);
            }
            lines.add(String.format(Locale.US, "%-5s %-18s %s%s · %s",
                    kind(name), shortened(name), count >= 0 ? count + " ev · " : "",
                    Formatter.formatShortFileSize(context, file.length()),
                    time.format(new Date(file.lastModified()))));
        }

        StringBuilder text = new StringBuilder(String.format(Locale.US,
                "Event storage: %d log(s), %d batch(es) holding %d event(s)", logs, batches, events));
        // The newest last, so those are the ones shown.
        int hidden = Math.max(0, lines.size() - MAX_ENTRIES_SHOWN);
        if (hidden > 0) {
            text.append(String.format(Locale.US, "\n… %d older", hidden));
        }
        for (String line : lines.subList(hidden, lines.size())) {
            text.append('\n').append(line);
        }
        view.setText(text);
    }

    private static String kind(String name) {
        if (name.startsWith(LOG_PREFIX)) {
            return "log";
        }
        if (name.startsWith(OPENING_PREFIX)) {
            return "new";
        }
        if (name.startsWith(BATCH_PREFIX)) {
            return "batch";
        }
        return "other";
    }

    /** The prefix and the start of the UUID after it, which is enough to tell files apart on a phone. */
    private static String shortened(String name) {
        int prefixEnd = name.startsWith(OWN_BATCH_PREFIX) ? OWN_BATCH_PREFIX.length() : name.indexOf('-') + 1;
        int end = prefixEnd + 8;
        return name.length() <= end + 1 ? name : name.substring(0, end) + "…";
    }

    /** What a batch's name says it holds, {@code ready-v1-<payloadId>.<count>}, or -1 for anything else. */
    private static int eventsInBatchNamed(String name) {
        if (!name.startsWith(OWN_BATCH_PREFIX)) {
            return -1;
        }
        String[] fields = name.substring(OWN_BATCH_PREFIX.length()).split("\\.", -1);
        if (fields.length < 2) {
            return -1;
        }
        try {
            return Integer.parseInt(fields[1]);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}

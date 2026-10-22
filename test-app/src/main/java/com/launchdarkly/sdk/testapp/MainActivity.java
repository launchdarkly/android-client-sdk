package com.launchdarkly.sdk.testapp;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.android.Components;
import com.launchdarkly.sdk.android.ConnectionInformation;
import com.launchdarkly.sdk.android.LDAllFlagsListener;
import com.launchdarkly.sdk.android.LDClient;
import com.launchdarkly.sdk.android.LDConfig;
import com.launchdarkly.sdk.android.LDConfig.Builder.AutoEnvAttributes;
import com.launchdarkly.sdk.android.LDFailure;
import com.launchdarkly.sdk.android.LDStatusListener;
import com.launchdarkly.sdk.android.integrations.DedupingHook;
import com.launchdarkly.sdk.android.integrations.EventPersistence;
import com.launchdarkly.sdk.android.integrations.EventProcessorBuilder;
import com.launchdarkly.sdk.android.integrations.LDCrashHandler;

import java.io.IOException;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import timber.log.Timber;

public class MainActivity extends AppCompatActivity {

    // Two hooks with different windows, to show that each one is deduplicated on its own.
    private static final int FAST_DEDUPE_WINDOW_MILLIS = 5_000;
    private static final int SLOW_DEDUPE_WINDOW_MILLIS = 10_000;

    // The staging hosts mirror the production ones in StandardEndpoints under the ld-stg domain.
    private static final String STAGING_DOMAIN = "ld-stg.launchdarkly.com";

    private static final String DEFAULT_USER_KEY = "user key";

    /** How long startup blocks waiting for the first flags to arrive. */
    private static final int INIT_WAIT_SECONDS = 10;

    private static final String PREFERENCES_NAME = "LaunchDarkly-testapp";
    private static final String PERSISTENCE_PREFERENCE = "eventPersistence";
    private static final EventPersistence DEFAULT_PERSISTENCE = EventPersistence.IMMEDIATE;

    /**
     * The default of {@code LDConfig.Builder#events} capacity. Past this the event store is full until a
     * flush empties it, so every further full event is dropped.
     */
    private static final int EVENT_CAPACITY = EventProcessorBuilder.DEFAULT_CAPACITY;

    /**
     * Enough evaluations past capacity for the average to settle, and few enough that even a slow device
     * finishes the tap well inside the ANR window.
     */
    private static final int OVER_CAPACITY_EVALUATIONS = 2000;

    /**
     * A batch holds at most 2 MiB, so seven of these fit in one and an eighth does not. Large events
     * rather than many small ones, because the 2000-event capacity is reached long before small events
     * fill a batch.
     */
    private static final int LARGE_EVENT_DATA_CHARS = 256 * 1024;

    /** Seven, seven and six: three batches, the last of them not full. */
    private static final int LARGE_EVENTS = 20;

    /** Null while a persistence change restarts the client, which makes every button a no-op meanwhile. */
    private volatile LDClient ldClient;
    private EventPersistence eventPersistence;
    private LDStatusListener ldStatusListener;
    private LDAllFlagsListener allFlagsListener;

    private final ExposureCountingHook fastHook =
            new ExposureCountingHook("fast", this::updateDedupeStatus);
    private final ExposureCountingHook slowHook =
            new ExposureCountingHook("slow", this::updateDedupeStatus);
    private final AtomicInteger evaluationsRequested = new AtomicInteger();

    private static boolean isStaging() {
        return "staging".equalsIgnoreCase(BuildConfig.LD_ENVIRONMENT);
    }

    private void updateDedupeStatus() {
        if (Looper.myLooper() != MainActivity.this.getMainLooper()) {
            new Handler(MainActivity.this.getMainLooper()).post(this::updateDedupeStatus);
            return;
        }

        String result = String.format(Locale.US,
                "Environment: %s\nEvaluations requested: %d\n%s\n%s",
                isStaging() ? "staging" : "production",
                evaluationsRequested.get(),
                fastHook.status(FAST_DEDUPE_WINDOW_MILLIS),
                slowHook.status(SLOW_DEDUPE_WINDOW_MILLIS));
        ((TextView) MainActivity.this.findViewById(R.id.dedupe_status)).setText(result);
    }

    private void updateStatusString(final ConnectionInformation connectionInformation) {
        if (Looper.myLooper() != MainActivity.this.getMainLooper()) {
            new Handler(MainActivity.this.getMainLooper()).post(() -> updateStatusString(connectionInformation));
        } else {
            TextView connection = MainActivity.this.findViewById(R.id.connection_status);
            Long lastSuccess = connectionInformation.getLastSuccessfulConnection();
            Long lastFailure = connectionInformation.getLastFailedConnection();

            String result = String.format(Locale.US, "Mode: %s\nSuccess at: %s\nFailure at: %s\nFailure type: %s",
                    connectionInformation.getConnectionMode().toString(),
                    lastSuccess == null ? "Never" : new Date(lastSuccess).toString(),
                    lastFailure == null ? "Never" : new Date(lastFailure).toString(),
                    connectionInformation.getLastFailure() != null ?
                            connectionInformation.getLastFailure().getFailureType()
                            : "");
            connection.setText(result);
        }
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        setupEval();
        setupFlushButton();
        setupTrackButton();
        setupIdentifyButton();
        setupKillUnsentButton();
        setupKillNowButton();
        setupCrashNowButton();
        setupOverRefreshButton();
        setupFillBatchesButton();
        setupOfflineSwitch();
        setupPersistenceSpinner();
        // Rescues the events for "Eval+Crash now" and cannot run for "Eval+Kill now", which is what
        // makes the pair worth pressing.
        LDCrashHandler.install(2, TimeUnit.SECONDS);
        setupListeners();
        updateDedupeStatus();

        if (BuildConfig.MOBILE_KEY.isEmpty()) {
            String message = "Set launchdarkly.mobileKey in local.properties and rebuild.";
            Timber.e(message);
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            return;
        }

        ldClient = startClient(eventPersistence);
    }

    /**
     * Blocks for up to {@link #INIT_WAIT_SECONDS} waiting for flags, and returns the client either way: if
     * they have not arrived by then, it is usable with whatever it has cached.
     */
    private LDClient startClient(EventPersistence persistence) {
        LDConfig.Builder configBuilder = new LDConfig.Builder(AutoEnvAttributes.Enabled)
                .mobileKey(BuildConfig.MOBILE_KEY)
                .http(
                        Components.httpConfiguration().useReport(false)
                        // change useReport to `true` if the request is to be REPORT'ed instead of GET'ed
                )
                .events(
                        Components.sendEvents().eventPersistence(persistence)
                )
                .hooks(
                        // Same shape a customer uses for any hook: wrap it at registration. Each
                        // wrapper has its own window, so neither suppresses the other.
                        Components.hooks()
                                .addHook(new DedupingHook(fastHook, FAST_DEDUPE_WINDOW_MILLIS))
                                .addHook(new DedupingHook(slowHook, SLOW_DEDUPE_WINDOW_MILLIS))
                );

        if (isStaging()) {
            configBuilder.serviceEndpoints(
                    Components.serviceEndpoints()
                            .streaming("https://clientstream." + STAGING_DOMAIN)
                            .polling("https://clientsdk." + STAGING_DOMAIN)
                            .events("https://mobile." + STAGING_DOMAIN)
            );
        }

        LDConfig ldConfig = configBuilder.build();

        LDContext context = LDContext.builder(DEFAULT_USER_KEY)
                .set("email", "fake@example.com")
                .build();

        LDClient client = LDClient.init(this.getApplication(), ldConfig, context, INIT_WAIT_SECONDS);
        Timber.i("client started with event persistence %s", persistence);
        updateStatusString(client.getConnectionInformation());
        client.registerStatusListener(ldStatusListener);
        client.registerAllFlagsListener(allFlagsListener);
        return client;
    }

    /**
     * The choice is saved rather than held in memory, because the kill and crash buttons are only
     * answered on the next launch, and that launch has to read the disk the same way: under
     * {@link EventPersistence#DISABLED} the events the dead process left there are not delivered.
     */
    private void setupPersistenceSpinner() {
        final SharedPreferences preferences = getSharedPreferences(PREFERENCES_NAME, MODE_PRIVATE);
        eventPersistence = parsePersistence(preferences.getString(PERSISTENCE_PREFERENCE, null));

        final Spinner spinner = findViewById(R.id.persistence_spinner);
        ArrayAdapter<EventPersistence> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_item, EventPersistence.values());
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(eventPersistence.ordinal(), false);

        spinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                EventPersistence chosen = (EventPersistence) parent.getItemAtPosition(position);
                if (chosen == eventPersistence) {
                    return;
                }
                eventPersistence = chosen;
                preferences.edit().putString(PERSISTENCE_PREFERENCE, chosen.name()).apply();
                restartClient(spinner, chosen);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    private static EventPersistence parsePersistence(String name) {
        if (name != null) {
            try {
                return EventPersistence.valueOf(name);
            } catch (IllegalArgumentException e) {
                Timber.w("ignoring saved event persistence %s", name);
            }
        }
        return DEFAULT_PERSISTENCE;
    }

    /**
     * Off the main thread, because closing waits on the event store and starting waits for flags. The
     * spinner stays disabled until it is done, so restarts never overlap.
     */
    private void restartClient(final Spinner spinner, final EventPersistence persistence) {
        if (BuildConfig.MOBILE_KEY.isEmpty()) {
            return;
        }
        final LDClient previous = ldClient;
        ldClient = null;
        spinner.setEnabled(false);
        new Thread(() -> {
            if (previous != null) {
                previous.unregisterStatusListener(ldStatusListener);
                previous.unregisterAllFlagsListener(allFlagsListener);
                try {
                    previous.close();
                } catch (IOException e) {
                    Timber.w(e, "closing the client for a persistence change");
                }
            }
            LDClient restarted = startClient(persistence);
            new Handler(getMainLooper()).post(() -> {
                ldClient = restarted;
                spinner.setEnabled(true);
                if (((Switch) findViewById(R.id.offlineSwitch)).isChecked()) {
                    restarted.setOffline();
                }
                Toast.makeText(this, "Restarted with " + persistence, Toast.LENGTH_SHORT).show();
            });
        }, "testapp-restart-client").start();
    }

    private void setupListeners() {
        ldStatusListener = new LDStatusListener() {
            @Override
            public void onConnectionModeChanged(final ConnectionInformation connectionInformation) {
                updateStatusString(connectionInformation);
            }

            @Override
            public void onInternalFailure(final LDFailure ldFailure) {
                new Handler(MainActivity.this.getMainLooper()).post(() -> {
                    Toast.makeText(MainActivity.this, ldFailure.toString(), Toast.LENGTH_SHORT).show();
                });
                MainActivity.this.doSafeClientAction(() -> updateStatusString(ldClient.getConnectionInformation()));
            }
        };

        allFlagsListener = flagKey -> {
            new Handler(MainActivity.this.getMainLooper()).post(() -> {
                StringBuilder flags = new StringBuilder("Updated flags: ");
                for (String flag : flagKey) {
                    flags.append(flag).append(" ");
                }
                Toast.makeText(MainActivity.this, flags.toString(), Toast.LENGTH_SHORT).show();
            });
            MainActivity.this.doSafeClientAction(() -> updateStatusString(ldClient.getConnectionInformation()));
        };
    }

    private void setupFlushButton() {
        Button flushButton = findViewById(R.id.flush_button);
        flushButton.setOnClickListener(v -> {
            Timber.i("flush onClick");
            MainActivity.this.doSafeClientAction(() -> ldClient.flush());
        });
    }

    private interface LDClientAction {
        void call();
    }

    private void doSafeClientAction(LDClientAction function) {
        if (ldClient != null) {
            function.call();
        }
    }

    private interface LDClientGet<V> {
        V get();
    }

    private <V> V doSafeClientGet(LDClientGet<V> function) {
        return ldClient != null ? function.get() : null;
    }

    private void setupTrackButton() {
        Button trackButton = findViewById(R.id.track_button);
        trackButton.setOnClickListener(v -> {
            Timber.i("track onClick");
            MainActivity.this.doSafeClientAction(() -> ldClient.track("Android event name"));
        });
    }

    /**
     * The flag the kill and crash buttons evaluate: whatever is typed in the feature key field, or a
     * default, so the buttons work without anything being typed first.
     */
    private String flagKeyToKillOver() {
        String typedKey = ((EditText) findViewById(R.id.feature_flag_key)).getText().toString().trim();
        return typedKey.isEmpty() ? "kill-flag" : typedKey;
    }

    /**
     * Records the pair whose survival is in question: an evaluation, which is the exposure, and a
     * track, standing in for the error an application reports just before it dies.
     *
     * <p>Returns false when there is no client, in which case nothing was recorded and ending the
     * process would demonstrate nothing.
     */
    private boolean recordExposureAndError(String flagKey) {
        if (ldClient == null) {
            return false;
        }
        ldClient.boolVariation(flagKey, false);
        ldClient.track("$ld:telemetry:error");
        return true;
    }

    /**
     * Reproduces in-memory event loss: evaluate (exposure) and track (stand-in for an error),
     * wait 5s so both calls are queued, then kill the process before the 30s flush.
     * {@code finish()} or backgrounding would run the SDK's background flush, so this uses
     * {@link android.os.Process#killProcess}.
     */
    private void setupKillUnsentButton() {
        Button killUnsentButton = findViewById(R.id.kill_unsent_button);
        killUnsentButton.setOnClickListener(v -> {
            final String flagKey = flagKeyToKillOver();
            Timber.w("eval+track+kill flag=%s", flagKey);
            if (!recordExposureAndError(flagKey)) {
                return;
            }
            ldClient.flush();
            new Handler(Looper.getMainLooper()).postDelayed(
                    () -> android.os.Process.killProcess(android.os.Process.myPid()),
                    5_000);
        });
    }

    /**
     * The same sequence with nothing at all between the track and the process dying: no flush to
     * deliver the events, no delay for a timer to fire in, and SIGKILL to itself, which cannot be
     * caught, so no part of the SDK gets to run on the way out.
     *
     * <p>Whether the exposure and the track are reported therefore says exactly one thing: whether
     * recording them had already put them somewhere that outlives the process. They should arrive on
     * the next launch of the app, not this one.
     */
    private void setupKillNowButton() {
        Button killNowButton = findViewById(R.id.kill_now_button);
        killNowButton.setOnClickListener(v -> {
            final String flagKey = flagKeyToKillOver();
            Timber.w("eval+track+kill now flag=%s", flagKey);
            if (!recordExposureAndError(flagKey)) {
                return;
            }
            android.os.Process.killProcess(android.os.Process.myPid());
        });
    }

    /**
     * The same again, ending in an uncaught exception instead of a signal the process never sees.
     *
     * <p>This is the shape a customer report takes: app code fails immediately after reporting the
     * failure. Unlike SIGKILL, an uncaught exception runs the default handler before the process
     * goes, so this is the one variant an application can rescue on its own, which
     * {@link LDCrashHandler} does by calling {@link LDClient#flushAndWait} from there. So these
     * events should arrive and the ones from the button next to it should not.
     */
    private void setupCrashNowButton() {
        Button crashNowButton = findViewById(R.id.crash_now_button);
        crashNowButton.setOnClickListener(v -> {
            final String flagKey = flagKeyToKillOver();
            Timber.w("eval+track+crash now flag=%s", flagKey);
            if (!recordExposureAndError(flagKey)) {
                return;
            }
            throw new RuntimeException(
                    "Eval+Crash: deliberate uncaught exception immediately after track, to test event persistence");
        });
    }

    /**
     * Reproduces the over-refresh pathology: a UI that re-evaluates one flag far more often than anything
     * about it changed -- a recomposition or layout loop -- against a flag whose events LaunchDarkly is
     * tracking. Runs on the main thread, because that is where the loop it stands in for runs.
     *
     * <p>The comparison between the two phases is the point rather than either number on its own. The first
     * {@link #EVENT_CAPACITY} evaluations have room in the event store; every one after that is dropped,
     * because nothing empties it until a flush. So the two phases costing the same per evaluation means the
     * SDK is serializing events it then discards, which is the whole of the waste. A cost of a couple of
     * microseconds instead means the flag has event tracking off, and this measured the summary counters
     * rather than the thing in question.
     */
    private void setupOverRefreshButton() {
        Button overRefreshButton = findViewById(R.id.over_refresh_button);
        overRefreshButton.setOnClickListener(v -> {
            if (ldClient == null) {
                return;
            }
            final String flagKey = "trackevents-test";
            Timber.w("over-refresh eval flag=%s", flagKey);

            double filling = averageMicroseconds(flagKey, EVENT_CAPACITY);
            double overCapacity = averageMicroseconds(flagKey, OVER_CAPACITY_EVALUATIONS);

            String result = String.format(Locale.US,
                    "Over-refresh %s\n%d filling: %.0f \u00b5s/eval\n%d over capacity: %.0f \u00b5s/eval",
                    flagKey,
                    EVENT_CAPACITY, filling,
                    OVER_CAPACITY_EVALUATIONS, overCapacity);
            Timber.w(result);
            ((TextView) findViewById(R.id.result_textView)).setText(result);
        });
    }

    /**
     * Records more events than one batch holds, so a flush sends several requests, each with a payload
     * ID of its own. Offline, or followed by a kill, it leaves several {@code ready-} files on disk for
     * the next launch to deliver. Each event's index is in its data, so the dashboard shows whether all
     * of them arrived and none twice.
     */
    private void setupFillBatchesButton() {
        Button fillBatchesButton = findViewById(R.id.fill_batches_button);
        fillBatchesButton.setOnClickListener(v -> {
            if (ldClient == null) {
                return;
            }
            char[] filler = new char[LARGE_EVENT_DATA_CHARS];
            Arrays.fill(filler, 'x');
            String padding = new String(filler);
            for (int i = 1; i <= LARGE_EVENTS; i++) {
                ldClient.trackData("fill-batches", LDValue.buildObject()
                        .put("index", i)
                        .put("of", LARGE_EVENTS)
                        .put("padding", padding)
                        .build());
            }
            String result = String.format(Locale.US,
                    "Recorded %d events of %d KiB: three batches of at most 2 MiB",
                    LARGE_EVENTS, LARGE_EVENT_DATA_CHARS / 1024);
            Timber.w(result);
            ((TextView) findViewById(R.id.result_textView)).setText(result);
        });
    }

    /**
     * @return the mean wall time, in microseconds, of {@code count} evaluations of the flag
     */
    private double averageMicroseconds(String flagKey, int count) {
        long start = System.nanoTime();
        for (int i = 0; i < count; i++) {
            ldClient.boolVariation(flagKey, false);
        }
        return (System.nanoTime() - start) / 1_000.0 / count;
    }

    private void setupIdentifyButton() {
        Button identify = findViewById(R.id.identify_button);
        identify.setOnClickListener(v -> {
            Timber.i("identify onClick");
            String typedKey = ((EditText) MainActivity.this.findViewById(R.id.userKey_editText))
                    .getText().toString().trim();
            // An empty key builds an invalid context, which identify rejects before it resets the
            // hooks' dedupe caches. Fall back to the key the client started with, so identifying to
            // an unchanged context still demonstrates that the reset happens either way.
            final String userKey = typedKey.isEmpty() ? DEFAULT_USER_KEY : typedKey;
            final LDContext updatedContext = LDContext.create(userKey);
            MainActivity.this.doSafeClientAction(() -> {
                ldClient.identify(updatedContext);
            });
            MainActivity.this.updateDedupeStatus();
        });
    }

    private void setupOfflineSwitch() {
        Switch offlineSwitch = findViewById(R.id.offlineSwitch);
        offlineSwitch.setOnCheckedChangeListener((compoundButton, isChecked) ->
            MainActivity.this.doSafeClientAction(isChecked ? () -> ldClient.setOffline() : () -> ldClient.setOnline())
        );
    }

    private void setupEval() {
        final Spinner spinner = findViewById(R.id.type_spinner);
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this,
                R.array.types_array, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);

        Button evalButton = findViewById(R.id.eval_button);
        evalButton.setOnClickListener(v -> {
            Timber.i("eval onClick");
            final String flagKey = ((EditText) MainActivity.this.findViewById(R.id.feature_flag_key)).getText().toString();
            evaluationsRequested.incrementAndGet();

            String type = spinner.getSelectedItem().toString();
            final String result;
            String logResult;
            switch (type) {
            case "String":
                result = MainActivity.this.doSafeClientGet(() -> ldClient.stringVariation(flagKey, "default"));
                logResult = result == null ? "no result" : result;
                Timber.i(logResult);
                ((TextView) MainActivity.this.findViewById(R.id.result_textView)).setText(result);
                MainActivity.this.doSafeClientAction(() -> {
                    ldClient.registerFeatureFlagListener(flagKey, flagKey1 -> {
                        evaluationsRequested.incrementAndGet();
                        ((TextView) MainActivity.this.findViewById(R.id.result_textView))
                                .setText(ldClient.stringVariation(flagKey1, "default"));
                        MainActivity.this.updateDedupeStatus();
                    });
                });
                MainActivity.this.updateDedupeStatus();
                return;
            case "Boolean":
                result = MainActivity.this.doSafeClientGet(() -> String.valueOf(ldClient.boolVariation(flagKey, false)));
                break;
            case "Integer":
                result = MainActivity.this.doSafeClientGet(() -> String.valueOf(ldClient.intVariation(flagKey, 0)));
                break;
            case "Float":
                result = MainActivity.this.doSafeClientGet(() -> String.valueOf(ldClient.doubleVariation(flagKey, 0.0)));
                break;
            case "Value":
                result = MainActivity.this.doSafeClientGet(() -> String.valueOf(ldClient.jsonValueVariation(flagKey, null)));
                break;
            default:
                result = null;
                break;
            }

            logResult = result == null ? "no result" : result;
            Timber.i(logResult);
            ((TextView) MainActivity.this.findViewById(R.id.result_textView)).setText(result);
            MainActivity.this.updateDedupeStatus();
        });
    }

}

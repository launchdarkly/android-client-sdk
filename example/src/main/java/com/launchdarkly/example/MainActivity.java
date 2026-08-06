package com.launchdarkly.example;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.launchdarkly.sdk.EvaluationDetail;
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
import com.launchdarkly.sdk.android.integrations.EvaluationSeriesContext;
import com.launchdarkly.sdk.android.integrations.Hook;

import java.util.Date;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import timber.log.Timber;

public class MainActivity extends AppCompatActivity {

    private static final int EVALUATION_EXPOSURE_DEDUPE_WINDOW_MILLIS = 5_000;

    // The staging hosts mirror the production ones in StandardEndpoints under the ld-stg domain.
    private static final String STAGING_DOMAIN = "ld-stg.launchdarkly.com";

    private LDClient ldClient;
    private LDStatusListener ldStatusListener;
    private LDAllFlagsListener allFlagsListener;

    private final ExposureCountingHook exposureHook = new ExposureCountingHook();
    private final AtomicInteger evaluationsRequested = new AtomicInteger();

    /**
     * Counts the evaluation hook stages so the example can show what exposure deduplication does.
     * Deduplication skips the whole series, so both counts stay equal and both stop climbing while
     * repeated evaluations resolve to the same result.
     */
    private class ExposureCountingHook extends Hook {
        final AtomicInteger befores = new AtomicInteger();
        final AtomicInteger afters = new AtomicInteger();

        ExposureCountingHook() {
            super("exposure-counting-hook");
        }

        @Override
        public Map<String, Object> beforeEvaluation(EvaluationSeriesContext seriesContext, Map<String, Object> seriesData) {
            befores.incrementAndGet();
            updateDedupeStatus();
            return seriesData;
        }

        @Override
        public Map<String, Object> afterEvaluation(EvaluationSeriesContext seriesContext, Map<String, Object> seriesData, EvaluationDetail<LDValue> evaluationDetail) {
            afters.incrementAndGet();
            updateDedupeStatus();
            return seriesData;
        }
    }

    private static boolean isStaging() {
        return "staging".equalsIgnoreCase(BuildConfig.LD_ENVIRONMENT);
    }

    private void updateDedupeStatus() {
        if (Looper.myLooper() != MainActivity.this.getMainLooper()) {
            new Handler(MainActivity.this.getMainLooper()).post(this::updateDedupeStatus);
            return;
        }

        int requested = evaluationsRequested.get();
        int reported = exposureHook.afters.get();
        String window = EVALUATION_EXPOSURE_DEDUPE_WINDOW_MILLIS > 0
                ? EVALUATION_EXPOSURE_DEDUPE_WINDOW_MILLIS + " ms"
                : "disabled";

        String result = String.format(Locale.US,
                "Environment: %s\nEvaluation Exposure dedupe window: %s\nEvaluations requested: %d\nReported to hooks: %d (before %d / after %d)",
                isStaging() ? "staging" : "production",
                window,
                requested,
                reported,
                exposureHook.befores.get(),
                reported);
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
        setupOfflineSwitch();
        setupListeners();
        updateDedupeStatus();

        if (BuildConfig.MOBILE_KEY.isEmpty()) {
            String message = "Set launchdarkly.mobileKey in local.properties and rebuild.";
            Timber.e(message);
            Toast.makeText(this, message, Toast.LENGTH_LONG).show();
            return;
        }

        LDConfig.Builder configBuilder = new LDConfig.Builder(AutoEnvAttributes.Enabled)
                .mobileKey(BuildConfig.MOBILE_KEY)
                .http(
                        Components.httpConfiguration().useReport(false)
                        // change useReport to `true` if the request is to be REPORT'ed instead of GET'ed
                )
                .hooks(
                        Components.hooks().addHook(exposureHook.evaluationExposureDeduper(
                                EVALUATION_EXPOSURE_DEDUPE_WINDOW_MILLIS, 2_000))
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

        LDContext context = LDContext.builder("user key")
                .set("email", "fake@example.com")
                .build();

        Future<LDClient> initFuture = LDClient.init(this.getApplication(), ldConfig, context);
        try {
            ldClient = initFuture.get(10, TimeUnit.SECONDS);
            updateStatusString(ldClient.getConnectionInformation());
            ldClient.registerStatusListener(ldStatusListener);
            ldClient.registerAllFlagsListener(allFlagsListener);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
            Timber.e(e, "Exception when awaiting LaunchDarkly Client initialization");
        }
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
                updateStatusString(ldClient.getConnectionInformation());
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
            updateStatusString(ldClient.getConnectionInformation());
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

    private void setupIdentifyButton() {
        Button identify = findViewById(R.id.identify_button);
        identify.setOnClickListener(v -> {
            Timber.i("identify onClick");
            String userKey = ((EditText) MainActivity.this.findViewById(R.id.userKey_editText)).getText().toString();
            final LDContext updatedContext = LDContext.create(userKey);
            MainActivity.this.doSafeClientAction(() -> ldClient.identify(updatedContext));
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

package com.launchdarkly.example;

import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import com.google.gson.JsonNull;
import com.launchdarkly.android.LDClient;
import com.launchdarkly.android.LDConfig;
import com.launchdarkly.android.LDUser;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private LDClient ldClient;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        setupEval();
        setupFlushButton();
        setupTrackButton();
        setupIdentifyButton();
        setupOfflineSwitch();

        LDConfig ldConfig = new LDConfig.Builder()
                .setMobileKey("MOBILE_KEY")
                .build();

        LDUser user = new LDUser.Builder("user key")
                .email("fake@example.com")
                .build();

        Future<LDClient> initFuture = LDClient.init(this.getApplication(), ldConfig, user);
        try {
            ldClient = initFuture.get(10, TimeUnit.SECONDS);
        } catch (InterruptedException | ExecutionException | TimeoutException e) {
           Log.e(TAG, "Exception when awaiting LaunchDarkly Client initialization", e);
        }
    }

    private void setupFlushButton() {
        Button flushButton = findViewById(R.id.flush_button);
        flushButton.setOnClickListener(v -> {
            Log.i(TAG, "flush onClick");
            ldClient.flush();
        });
    }

    private void setupTrackButton() {
        Button trackButton = findViewById(R.id.track_button);
        trackButton.setOnClickListener(v -> {
            Log.i(TAG, "track onClick");
            ldClient.track("Android event name");
        });
    }

    private void setupIdentifyButton() {
        Button identify = findViewById(R.id.identify_button);
        identify.setOnClickListener(v -> {
                Log.i(TAG, "identify onClick");
                String userKey = ((EditText) findViewById(R.id.userKey_editText)).getText().toString();

                LDUser updatedUser = new LDUser.Builder(userKey)
                        .build();

                ldClient.identify(updatedUser);
            });
    }

    private void setupOfflineSwitch() {
        Switch offlineSwitch = findViewById(R.id.offlineSwitch);
        offlineSwitch.setOnCheckedChangeListener((compoundButton, isChecked) -> {
            if (isChecked) {
                ldClient.setOffline();
            } else {
                ldClient.setOnline();
            }
        });
    }

    private void setupEval() {
        final Spinner spinner = findViewById(R.id.type_spinner);
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this,
                R.array.types_array, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);

        Button evalButton = findViewById(R.id.eval_button);
        evalButton.setOnClickListener(v -> {
            Log.i(TAG, "eval onClick");
            final String flagKey = ((EditText) findViewById(R.id.feature_flag_key)).getText().toString();

            String type = spinner.getSelectedItem().toString();
            final String result;
            switch (type) {
                case "String":
                    result = ldClient.stringVariation(flagKey, "default");
                    Log.i(TAG, result);
                    ((TextView) findViewById(R.id.result_textView)).setText(result);
                    ldClient.registerFeatureFlagListener(flagKey, flagKey1 -> ((TextView) findViewById(R.id.result_textView))
                            .setText(ldClient.stringVariation(flagKey1, "default")));
                    break;
                case "Boolean":
                    result = ldClient.boolVariation(flagKey, false).toString();
                    Log.i(TAG, result);
                    ((TextView) findViewById(R.id.result_textView)).setText(result);
                    break;
                case "Integer":
                    result = ldClient.intVariation(flagKey, 0).toString();
                    Log.i(TAG, result);
                    ((TextView) findViewById(R.id.result_textView)).setText(result);
                    break;
                case "Float":
                    result = ldClient.floatVariation(flagKey, 0F).toString();
                    Log.i(TAG, result);
                    ((TextView) findViewById(R.id.result_textView)).setText(result);
                    break;
                case "Json":
                    result = ldClient.jsonVariation(flagKey, JsonNull.INSTANCE).toString();
                    Log.i(TAG, result);
                    ((TextView) findViewById(R.id.result_textView)).setText(result);
                    break;
            }
        });
    }

}

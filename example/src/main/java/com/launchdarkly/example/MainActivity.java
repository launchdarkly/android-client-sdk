package com.launchdarkly.example;

import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import com.google.gson.JsonNull;
import com.launchdarkly.android.FeatureFlagChangeListener;
import com.launchdarkly.android.LDClient;
import com.launchdarkly.android.LDConfig;
import com.launchdarkly.android.LDUser;

import java.io.IOException;
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
                .setUseReport(false) // change to `true` if the request is to be REPORT'ed instead of GET'ed
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

    @Override
    public void onStop() {
        super.onStop();
        doSafeClientAction(new LDClientFunction() {
            @Override
            public void call() {
                try {
                    ldClient.close();
                } catch (IOException e) {
                    Log.e(TAG, "Exception when closing LaunchDarkly Client", e);
                }
            }
        });
    }

    private void setupFlushButton() {
        Button flushButton = findViewById(R.id.flush_button);
        flushButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Log.i(TAG, "flush onClick");
                MainActivity.this.doSafeClientAction(new LDClientFunction() {
                    @Override
                    public void call() {
                        ldClient.flush();
                    }
                });
            }
        });
    }

    private interface LDClientFunction {
        void call();
    }

    private interface LDClientGetFunction<V> {
        V get();
    }

    private void doSafeClientAction(LDClientFunction function) {
        if (ldClient != null) {
            function.call();
        }
    }

    @Nullable
    private <V> V doSafeClientGet(LDClientGetFunction<V> function) {
        if (ldClient != null) {
            return function.get();
        }
        return null;
    }

    private void setupTrackButton() {
        Button trackButton = findViewById(R.id.track_button);
        trackButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Log.i(TAG, "track onClick");
                MainActivity.this.doSafeClientAction(new LDClientFunction() {
                    @Override
                    public void call() {
                        ldClient.track("Android event name");
                    }
                });
            }
        });
    }

    private void setupIdentifyButton() {
        Button identify = findViewById(R.id.identify_button);
        identify.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Log.i(TAG, "identify onClick");
                String userKey = ((EditText) MainActivity.this.findViewById(R.id.userKey_editText)).getText().toString();
                final LDUser updatedUser = new LDUser.Builder(userKey).build();
                MainActivity.this.doSafeClientAction(new LDClientFunction() {
                    @Override
                    public void call() {
                        ldClient.identify(updatedUser);
                    }
                });
            }
        });
    }

    private void setupOfflineSwitch() {
        Switch offlineSwitch = findViewById(R.id.offlineSwitch);
        offlineSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton compoundButton, boolean isChecked) {
                if (isChecked) {
                    MainActivity.this.doSafeClientAction(new LDClientFunction() {
                        @Override
                        public void call() {
                            ldClient.setOffline();
                        }
                    });
                } else {
                    MainActivity.this.doSafeClientAction(new LDClientFunction() {
                        @Override
                        public void call() {
                            ldClient.setOnline();
                        }
                    });
                }
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
        evalButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Log.i(TAG, "eval onClick");
                final String flagKey = ((EditText) MainActivity.this.findViewById(R.id.feature_flag_key)).getText().toString();

                String type = spinner.getSelectedItem().toString();
                final String result;
                String logResult;
                switch (type) {
                    case "String":
                        result = MainActivity.this.doSafeClientGet(new LDClientGetFunction<String>() {
                            @Override
                            public String get() {
                                return ldClient.stringVariation(flagKey, "default");
                            }
                        });
                        logResult = result == null ? "no result" : result;
                        Log.i(TAG, logResult);
                        ((TextView) MainActivity.this.findViewById(R.id.result_textView)).setText(result);
                        MainActivity.this.doSafeClientAction(new LDClientFunction() {
                            @Override
                            public void call() {
                                ldClient.registerFeatureFlagListener(flagKey, new FeatureFlagChangeListener() {
                                    @Override
                                    public void onFeatureFlagChange(String flagKey1) {
                                        ((TextView) MainActivity.this.findViewById(R.id.result_textView))
                                                .setText(ldClient.stringVariation(flagKey1, "default"));
                                    }
                                });
                            }
                        });
                        break;
                    case "Boolean":
                        result = MainActivity.this.doSafeClientGet(new LDClientGetFunction<String>() {
                            @Override
                            public String get() {
                                return ldClient.boolVariation(flagKey, false).toString();
                            }
                        });
                        logResult = result == null ? "no result" : result;
                        Log.i(TAG, logResult);
                        ((TextView) MainActivity.this.findViewById(R.id.result_textView)).setText(result);
                        break;
                    case "Integer":
                        result = MainActivity.this.doSafeClientGet(new LDClientGetFunction<String>() {
                            @Override
                            public String get() {
                                return ldClient.intVariation(flagKey, 0).toString();
                            }
                        });
                        logResult = result == null ? "no result" : result;
                        Log.i(TAG, logResult);
                        ((TextView) MainActivity.this.findViewById(R.id.result_textView)).setText(result);
                        break;
                    case "Float":
                        result = MainActivity.this.doSafeClientGet(new LDClientGetFunction<String>() {
                            @Override
                            public String get() {
                                return ldClient.floatVariation(flagKey, 0F).toString();
                            }
                        });
                        logResult = result == null ? "no result" : result;
                        Log.i(TAG, logResult);
                        ((TextView) MainActivity.this.findViewById(R.id.result_textView)).setText(result);
                        break;
                    case "Json":
                        result = MainActivity.this.doSafeClientGet(new LDClientGetFunction<String>() {
                            @Override
                            public String get() {
                                return ldClient.jsonVariation(flagKey, JsonNull.INSTANCE).toString();
                            }
                        });
                        logResult = result == null ? "no result" : result;
                        Log.i(TAG, logResult);
                        ((TextView) MainActivity.this.findViewById(R.id.result_textView)).setText(result);
                        break;
                }
            }
        });
    }

}

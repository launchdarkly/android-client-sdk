package com.launchdarkly.example;

import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

import com.google.gson.JsonNull;
import com.launchdarkly.android.LDClient;
import com.launchdarkly.android.LDConfig;
import com.launchdarkly.android.LDUser;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private LDClient ldClient;
    private LDUser user;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        setupEval();
        setupFlushButton();
        setupTrackButton();
        setupIdentifyButton();

        LDConfig ldConfig = new LDConfig.Builder()
                .setMobileKey("mob-342db898-15fa-4057-a058-2a340d85173e")
                .build();

        user = new LDUser.Builder("user key")
                .email("fake@example.com")
                .build();

        ldClient = LDClient.init(this.getApplication(), ldConfig, user);
    }

    private void setupFlushButton() {
        Button flushButton = (Button) findViewById(R.id.flush_button);
        flushButton.setOnClickListener(new View.OnClickListener() {

            @Override
            public void onClick(View v) {
                Log.i(TAG, "flush onClick");
                ldClient.flush();
            }
        });
    }

    private void setupTrackButton() {
        Button trackButton = (Button) findViewById(R.id.track_button);
        trackButton.setOnClickListener(new View.OnClickListener() {

            @Override
            public void onClick(View v) {
                Log.i(TAG, "track onClick");
                ldClient.track("Android event name");
            }
        });
    }

    private void setupIdentifyButton() {
        Button identify = (Button) findViewById(R.id.identify_button);
        identify.setOnClickListener(new View.OnClickListener() {

            @Override
            public void onClick(View v) {
                Log.i(TAG, "identify onClick");
                String userKey = ((EditText) findViewById(R.id.userKey_editText)).getText().toString();

                LDUser updatedUser = new LDUser.Builder(userKey)
                        .build();

                ldClient.identify(updatedUser);
            }
        });
    }

    private void setupEval() {
        final Spinner spinner = (Spinner) findViewById(R.id.type_spinner);
        ArrayAdapter<CharSequence> adapter = ArrayAdapter.createFromResource(this,
                R.array.types_array, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);

        Button evalButton = (Button) findViewById(R.id.eval_button);
        evalButton.setOnClickListener(new View.OnClickListener() {

            @Override
            public void onClick(View v) {
                Log.i(TAG, "eval onClick");
                String featureKey = ((EditText) findViewById(R.id.feature_flag_key)).getText().toString();

                String type = spinner.getSelectedItem().toString();
                String result;
                switch (type) {
                    case "String":
                        result = ldClient.stringVariation(featureKey, "default");
                        Log.i(TAG, result);
                        ((TextView) findViewById(R.id.result_textView)).setText(result);
                        break;
                    case "Boolean":
                        result = ldClient.boolVariation(featureKey, false).toString();
                        Log.i(TAG, result);
                        ((TextView) findViewById(R.id.result_textView)).setText(result);
                        break;
                    case "Integer":
                        result = ldClient.intVariation(featureKey, 0).toString();
                        Log.i(TAG, result);
                        ((TextView) findViewById(R.id.result_textView)).setText(result);
                        break;
                    case "Float":
                        result = ldClient.floatVariation(featureKey, 0F).toString();
                        Log.i(TAG, result);
                        ((TextView) findViewById(R.id.result_textView)).setText(result);
                        break;
                    case "Json":
                        result = ldClient.jsonVariation(featureKey, JsonNull.INSTANCE).toString();
                        Log.i(TAG, result);
                        ((TextView) findViewById(R.id.result_textView)).setText(result);
                        break;
                }
            }
        });
    }

}

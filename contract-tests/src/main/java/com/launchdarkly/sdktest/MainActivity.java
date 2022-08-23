package com.launchdarkly.sdktest;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.android.LDAndroidLogging;

import java.io.IOException;

public class MainActivity extends Activity {
    private Config config;
    private TestService server;
    private LDLogger logger = LDLogger.withAdapter(LDAndroidLogging.adapter(), "MainActivity");

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        config = Config.fromArgs(getIntent().getExtras());

        TextView textIpaddr = findViewById(R.id.ipaddr);
        textIpaddr.setText("Contract test service running on port " + config.port);
    }

    @Override
    public void onStop() {
        super.onStop();
        if (server != null) {
            server.stop();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        logger.warn("Restarting test service on port {}", config.port);
        server = new TestService(getApplication());
        if (!server.isAlive()) {
            try {
                server.start();
            } catch (IOException e) {
                logger.error("Error starting server: {}", e);
            }
        }
    }
}

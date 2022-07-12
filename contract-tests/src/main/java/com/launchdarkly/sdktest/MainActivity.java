package com.launchdarkly.sdktest;

import android.app.Activity;
import android.os.Bundle;
import android.widget.TextView;

import java.io.IOException;

import timber.log.Timber;

public class MainActivity extends Activity {

    private Config config;
    private TestService server;
    private Timber.DebugTree debugTree;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        config = Config.fromArgs(getIntent().getExtras());

        TextView textIpaddr = findViewById(R.id.ipaddr);
        textIpaddr.setText("Contract test service running on port " + config.port);

        if (Timber.treeCount() == 0) {
            debugTree = new Timber.DebugTree();
            Timber.plant(debugTree);
        }
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
        Timber.w("Restarting test service on port " + config.port);
        server = new TestService(getApplication());
        if (!server.isAlive()) {
            try {
                server.start();
            } catch (IOException e) {
                Timber.e(e, "Error starting server");
            }
        }
    }
}

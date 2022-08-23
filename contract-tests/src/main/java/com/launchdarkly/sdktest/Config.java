package com.launchdarkly.sdktest;

import android.os.Bundle;

/**
 * Contains the configuration values passed to the test service at runtime.
 * Currently only contains the port number.
 */
public class Config {
    public int port = 35001;

    public static Config fromArgs(Bundle params) {
        Config ret = new Config();

        String portStr = params == null ? null : params.getString("PORT");
        if (portStr != null) {
            ret.port = Integer.parseInt(portStr);
        }

        return ret;
    }
}

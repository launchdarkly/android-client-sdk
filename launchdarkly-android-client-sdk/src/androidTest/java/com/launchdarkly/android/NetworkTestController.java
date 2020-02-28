package com.launchdarkly.android;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.wifi.WifiManager;

import java.lang.reflect.Method;

/**
 * Test fixture for allowing testing of behavior dependent on the SDK detecting network status. As
 * it's no longer possible to configure the mobile data connection after API 21, this will only work
 * pre API 21 or when the mobile data is configured to be off on the emulator/device hosting the
 * tests. This can be accomplished in an automated environment using the adb command:
 * <pre>adb shell svc data disable</pre>
 */
public class NetworkTestController {

    private static Context context;
    private static WifiManager wifiManager;
    private static ConnectivityManager connectivityManager;

    public static void setup(Context context) {
        NetworkTestController.context = context;
        wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        connectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
    }

    public static void enableNetwork() throws InterruptedException {
        setNetwork(true);
        // Wait for update to happen
        while (!LDUtil.isInternetConnected(context)) {
            Thread.sleep(10);
        }
    }

    public static void disableNetwork() throws InterruptedException {
        setNetwork(false);
        // Wait for update to happen
        while (LDUtil.isInternetConnected(context)) {
            Thread.sleep(10);
        }
    }

    public static void setNetwork(boolean network) {
        wifiManager.setWifiEnabled(network);
        // This no longer works after API 21
        try {
            Method setData = ConnectivityManager.class.getDeclaredMethod("setMobileDataEnabled", boolean.class);
            setData.setAccessible(true);
            setData.invoke(connectivityManager, network);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}

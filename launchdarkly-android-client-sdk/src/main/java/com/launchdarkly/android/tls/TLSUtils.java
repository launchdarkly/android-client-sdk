package com.launchdarkly.android.tls;

import android.app.Application;

import com.google.android.gms.common.GooglePlayServicesNotAvailableException;
import com.google.android.gms.common.GooglePlayServicesRepairableException;
import com.google.android.gms.security.ProviderInstaller;

import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import timber.log.Timber;

@Deprecated
public class TLSUtils {

    public static X509TrustManager defaultTrustManager() throws GeneralSecurityException {
        TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        trustManagerFactory.init((KeyStore) null);
        TrustManager[] trustManagers = trustManagerFactory.getTrustManagers();
        if (trustManagers.length != 1 || !(trustManagers[0] instanceof X509TrustManager)) {
            throw new IllegalStateException("Unexpected default trust managers:"
                    + Arrays.toString(trustManagers));
        }
        return (X509TrustManager) trustManagers[0];
    }

    public static void patchTLSIfNeeded(Application application) {
        try {
            SSLContext.getInstance("TLSv1.2");
        } catch (NoSuchAlgorithmException e) {
            Timber.w("No TLSv1.2 implementation available, attempting patch.");
            try {
                ProviderInstaller.installIfNeeded(application.getApplicationContext());
            } catch (GooglePlayServicesRepairableException e1) {
                Timber.w("Patch failed, Google Play Services too old.");
            } catch (GooglePlayServicesNotAvailableException e1) {
                Timber.w("Patch failed, no Google Play Services available.");
            }
        }
    }

}

package com.launchdarkly.android;

import android.app.Application;
import androidx.annotation.NonNull;

import com.google.android.gms.common.GooglePlayServicesNotAvailableException;
import com.google.android.gms.common.GooglePlayServicesRepairableException;
import com.google.android.gms.security.ProviderInstaller;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.security.GeneralSecurityException;
import java.security.KeyManagementException;
import java.security.KeyStore;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import okhttp3.CipherSuite;
import okhttp3.Handshake;
import okhttp3.Response;
import okhttp3.TlsVersion;
import timber.log.Timber;

class TLSUtils {

    static X509TrustManager defaultTrustManager() throws GeneralSecurityException {
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

    static void patchTLSIfNeeded(Application application) {
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

/**
 * Intercepts the SSL connection and prints TLS version and CipherSuite in the log.
 */
class SSLHandshakeInterceptor implements okhttp3.Interceptor {

    @Override
    public Response intercept(@NonNull Chain chain) throws IOException {
        final Response response = chain.proceed(chain.request());
        printTlsAndCipherSuiteInfo(response);
        return response;
    }

    private void printTlsAndCipherSuiteInfo(Response response) {
        if (response != null) {
            Handshake handshake = response.handshake();
            if (handshake != null) {
                final CipherSuite cipherSuite = handshake.cipherSuite();
                final TlsVersion tlsVersion = handshake.tlsVersion();
                Timber.v("TLS: %s, CipherSuite: %s", tlsVersion, cipherSuite);
            }
        }
    }
}

/**
 * An {@link SSLSocketFactory} that tries to ensure modern TLS versions are used.
 */
class ModernTLSSocketFactory extends SSLSocketFactory {
    private static final String TLS_1_2 = "TLSv1.2";
    private static final String TLS_1_1 = "TLSv1.1";
    private static final String TLS_1 = "TLSv1";

    private SSLSocketFactory defaultSocketFactory;

    public ModernTLSSocketFactory() throws NoSuchAlgorithmException, KeyManagementException {
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, null, null);
        this.defaultSocketFactory = context.getSocketFactory();
    }

    @Override
    public String[] getDefaultCipherSuites() {
        return this.defaultSocketFactory.getDefaultCipherSuites();
    }

    @Override
    public String[] getSupportedCipherSuites() {
        return this.defaultSocketFactory.getSupportedCipherSuites();
    }

    @Override
    public Socket createSocket(Socket socket, String s, int i, boolean b) throws IOException {
        return setModernTlsVersionsOnSocket(this.defaultSocketFactory.createSocket(socket, s, i, b));
    }

    @Override
    public Socket createSocket(String s, int i) throws IOException, UnknownHostException {
        return setModernTlsVersionsOnSocket(this.defaultSocketFactory.createSocket(s, i));
    }

    @Override
    public Socket createSocket(String s, int i, InetAddress inetAddress, int i1) throws IOException, UnknownHostException {
        return setModernTlsVersionsOnSocket(this.defaultSocketFactory.createSocket(s, i, inetAddress, i1));
    }

    @Override
    public Socket createSocket(InetAddress inetAddress, int i) throws IOException {
        return setModernTlsVersionsOnSocket(this.defaultSocketFactory.createSocket(inetAddress, i));
    }

    @Override
    public Socket createSocket(InetAddress inetAddress, int i, InetAddress inetAddress1, int i1) throws IOException {
        return setModernTlsVersionsOnSocket(this.defaultSocketFactory.createSocket(inetAddress, i, inetAddress1, i1));
    }

    /**
     * If either of TLSv1.2, TLSv1.1, or TLSv1 are supported, make them the only enabled protocols (listing in that order).
     * <p>
     * If the socket does not make these modern TLS protocols available at all, then just return the socket unchanged.
     *
     * @param s the socket
     * @return the socket with modern TLS protocols enabled if possible
     */
    private static Socket setModernTlsVersionsOnSocket(Socket s) {
        if (s instanceof SSLSocket) {
            List<String> defaultEnabledProtocols = Arrays.asList(((SSLSocket) s).getSupportedProtocols());
            ArrayList<String> newEnabledProtocols = new ArrayList<>();
            if (defaultEnabledProtocols.contains(TLS_1_2)) {
                newEnabledProtocols.add(TLS_1_2);
            }
            if (defaultEnabledProtocols.contains(TLS_1_1)) {
                newEnabledProtocols.add(TLS_1_1);
            }
            if (defaultEnabledProtocols.contains(TLS_1)) {
                newEnabledProtocols.add(TLS_1);
            }
            if (newEnabledProtocols.size() > 0) {
                ((SSLSocket) s).setEnabledProtocols(newEnabledProtocols.toArray(new String[0]));
            }
        }
        return s;
    }
}
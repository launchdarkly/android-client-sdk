package com.launchdarkly.sdk.android.env;

import android.app.Application;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.annotation.NonNull;

import com.launchdarkly.sdk.android.LDPackageConsts;
import com.launchdarkly.sdk.android.subsystems.ApplicationInfo;

import java.util.Locale;

/**
 * An {@link IEnvironmentReporter} that can fetch environment attributes from Android platform.
 */
class AndroidEnvironmentReporter extends EnvironmentReporterChainBase implements IEnvironmentReporter {

    // application reference used to make API queries
    @NonNull
    private final Application application;

    /**
     * @param application that represents the Android application this code is running in.
     */
    public AndroidEnvironmentReporter(Application application) {
        this.application = application;
    }

    @Override
    @NonNull
    public ApplicationInfo getApplicationInfo() {
        return new ApplicationInfo(
                getApplicationID(),
                getApplicationName(),
                getApplicationVersion(),
                getApplicationVersionName()
        );
    }

    @NonNull
    @Override
    public String getManufacturer() {
        return Build.MANUFACTURER;
    }

    @NonNull
    @Override
    public String getModel() {
        return Build.MODEL;
    }

    @NonNull
    @Override
    public String getLocale() {
        Locale locale;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            locale = application.getResources().getConfiguration().getLocales().get(0);
        } else {
            locale = application.getResources().getConfiguration().locale;
        }
        return locale.toLanguageTag();
    }

    @NonNull
    @Override
    public String getOSFamily() {
        return LDPackageConsts.SDK_PLATFORM_NAME;
    }

    @NonNull
    @Override
    public String getOSVersion() {
        return Build.VERSION.RELEASE;
    }

    @NonNull
    private String getApplicationID() {
        return application.getPackageName();
    }

    @NonNull
    private String getApplicationName() {
        try {
            PackageManager pm = application.getPackageManager();
            android.content.pm.ApplicationInfo ai = pm.getApplicationInfo(application.getPackageName(), 0);
            return pm.getApplicationLabel(ai).toString();
        } catch (PackageManager.NameNotFoundException e) {
            // TODO: investigate if this runtime exception can actually happen since we just
            // got the package name just before.  Current gut feeling says not possible, but
            // those are famous last words.
            throw new RuntimeException(e);
        }
    }

    @NonNull
    private String getApplicationVersion() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                return String.valueOf(application.getPackageManager().getPackageInfo(application.getPackageName(), 0).getLongVersionCode());
            } else {
                return String.valueOf(application.getPackageManager().getPackageInfo(application.getPackageName(), 0).versionCode);
            }
        } catch (PackageManager.NameNotFoundException e) {
            // TODO: investigate if this runtime exception can actually happen since we just
            // got the package name just before.  Current gut feeling says not possible, but
            // those are famous last words.
            throw new RuntimeException(e);
        }
    }

    @NonNull
    private String getApplicationVersionName() {
        try {
            PackageManager pm = application.getPackageManager();
            return pm.getPackageInfo(application.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            // TODO: figure out if there is a way to get around this exception more elegantly.
            throw new RuntimeException(e);
        }
    }
}

package com.launchdarkly.sdk.android;

import android.app.Application;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.launchdarkly.sdk.android.subsystems.ApplicationInfo;

import java.util.Locale;

// TODO: Come up with better name than EnvironmentReporter.  This is confusing since LD has environments
// as a different concept
public class EnvironmentReporter {

    // TODO: Should we rename EnvironmentReporter to SDKEnvironmentReporter
    private Application application;
    private ApplicationInfo applicationInfo;

    /**
     * Creates an environment reporter.
     * @param application that represents the application environment this code is running in.
     */
    public EnvironmentReporter(Application application) {
        this.application = application;
    }

    /**
     * Sets the application info that this environment reporter will report when asked in the future.
     * @param applicationInfo to report.
     */
    public void setApplicationInfo(ApplicationInfo applicationInfo) {
        this.applicationInfo = applicationInfo;
    }

    /**
     * Gets the {@link ApplicationInfo} for the application environment.  If no {@link ApplicationInfo}
     * has been provided via {@link #setApplicationInfo(ApplicationInfo)}, the {@link EnvironmentReporter}
     * will collect application info automatically.
     */
    @NonNull
    public ApplicationInfo getApplicationInfo() {
        // First priority is to return the application info that was provided manually.
        // Second priority is to use application info fetched from the Android API.
        if (applicationInfo == null) {
            applicationInfo = new com.launchdarkly.sdk.android.subsystems.ApplicationInfo(
                    getApplicationID(),
                    getApplicationName(),
                    getApplicationVersion(),
                    getApplicationVersionName()
            );
        }

        return applicationInfo;
    }

    private String getApplicationID() {
        return application.getPackageName();
    }

    private String getApplicationName() {
        try {
            PackageManager pm = application.getPackageManager();
            android.content.pm.ApplicationInfo ai = pm.getApplicationInfo( application.getPackageName(), 0);
            return pm.getApplicationLabel(ai).toString();
        } catch (PackageManager.NameNotFoundException e) {
            // TODO: investigate if this runtime exception can actually happen since we just
            // got the package name just before.  Current gut feeling says not possible, but
            // those are famous last words.
            throw new RuntimeException(e);
        }
    }

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

    private String getApplicationVersionName() {
        try {
            PackageManager pm = application.getPackageManager();
            return pm.getPackageInfo( application.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            // TODO: figure out if there is a way to get around this exception more elegantly.
            throw new RuntimeException(e);
        }
    }

    public String getManufacturer() {
        return Build.MANUFACTURER;
    }

    public String getModel() {
        return Build.MODEL;
    }

    /**
     * @return a BCP47 language tag representing the locale
     */
    public String getLocale() {
        Locale locale;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N){
            locale = application.getResources().getConfiguration().getLocales().get(0);
        } else{
            locale = application.getResources().getConfiguration().locale;
        }
        return locale.toLanguageTag();
    }

    public String getOSFamily() {
        return "Android";
    }

    public String getOSVersion() {
        return Build.VERSION.RELEASE;
    }
}

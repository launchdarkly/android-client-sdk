package com.launchdarkly.sdk.android.env;

import android.app.Application;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.annotation.NonNull;

import com.launchdarkly.sdk.android.LDPackageConsts;
import com.launchdarkly.sdk.android.integrations.ApplicationInfoBuilder;
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
        // use a builder here since it has sanitization and validation built in
        ApplicationInfoBuilder builder = new ApplicationInfoBuilder();
        builder.applicationId(getApplicationID());
        builder.applicationVersion(getApplicationVersion());
        builder.applicationName(getApplicationName());
        builder.applicationVersionName(getApplicationVersionName());
        ApplicationInfo info = builder.createApplicationInfo();

        // defer to super if required property applicationID is missing
        if (info.getApplicationId() == null) {
            info = super.getApplicationInfo();
        }

        return info;
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
    public String getOSName() {
        return LDPackageConsts.SDK_PLATFORM_NAME + Build.VERSION.SDK_INT;
    }

    @NonNull
    @Override
    public String getOSVersion() {
        return Build.VERSION.RELEASE;
    }

    private String getApplicationID() {
        return application.getPackageName();
    }

    private String getApplicationName() {
        try {
            PackageManager pm = application.getPackageManager();
            android.content.pm.ApplicationInfo ai = pm.getApplicationInfo(application.getPackageName(), 0);
            return pm.getApplicationLabel(ai).toString();
        } catch (PackageManager.NameNotFoundException e) {
            // We don't really expect this to ever happen since we just queried the platform
            // for the application name and then immediately used it.  Since the code has
            // this logical path, the best we can do is defer to the next in the chain.
            return super.getApplicationInfo().getApplicationName();
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
            // We don't really expect this to ever happen since we just queried the platform
            // for the application name and then immediately used it.  It is best to set
            // this to null, if enough properties are missing, we will fallback to the
            // next source in the chain.
            return null;
        }
    }

    private String getApplicationVersionName() {
        try {
            PackageManager pm = application.getPackageManager();
            return pm.getPackageInfo(application.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            // We don't really expect this to ever happen since we just queried the platform
            // for the application name and then immediately used it.  It is best to set
            // this to null, if enough properties are missing, we will fallback to the
            // next source in the chain.
            return null;
        }
    }
}

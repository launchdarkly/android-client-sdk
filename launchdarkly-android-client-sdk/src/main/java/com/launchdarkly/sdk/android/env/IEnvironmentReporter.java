package com.launchdarkly.sdk.android.env;

import androidx.annotation.NonNull;

import com.launchdarkly.sdk.android.subsystems.ApplicationInfo;

/**
 * Reports information about the software/hardware environment that the SDK is
 * executing within.
 */
public interface IEnvironmentReporter {

    /**
     * @return the {@link ApplicationInfo} for the application environment.
     */
    @NonNull
    ApplicationInfo getApplicationInfo();

    /**
     * @return the manufacturer of the device the application is running in
     */
    @NonNull
    String getManufacturer();

    /**
     * @return the model of the device the application is running in
     */
    @NonNull
    String getModel();

    /**
     * @return a BCP47 language tag representing the locale
     */
    @NonNull
    String getLocale();

    /**
     * @return the OS Family that this application is running in
     */
    @NonNull
    String getOSFamily();

    /**
     * @return the name of the OS that this application is running in
     */
    @NonNull
    String getOSName();

    /**
     * @return the version of the OS that this application is running in
     */
    @NonNull
    String getOSVersion();
}

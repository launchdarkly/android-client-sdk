package com.launchdarkly.sdk.android;

/**
 * Told what the flag data source is doing, so that events can give way to flag values.
 * <p>
 * {@link ConnectivityManager} reports to the event processor through this when the processor implements
 * it, as {@link DirectEventProcessor} does. A custom event processor does not, and hears nothing.
 */
interface FlagRequestListener {
    /** How a flag request ended. */
    enum Outcome {
        /** Flags arrived, or the service confirmed the ones held. */
        LOADED,
        /** An error response, a network failure, or a timeout. */
        FAILED,
        /** The data source was stopped before it answered: the SDK went offline, or switched to another. */
        STOPPED
    }

    /** A data source started loading flags: at launch, at {@code identify}, or reconnecting. */
    void flagRequestStarted();

    /** The flag request most recently started has ended. */
    void flagRequestSettled(Outcome outcome);
}

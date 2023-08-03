package com.launchdarkly.sdk.android;

import java.util.List;

/**
 * Callback interface used for listening to changes to the flag store.
 */
@FunctionalInterface
public interface LDAllFlagsListener {

    /**
     * Called by the SDK whenever it receives an update for the stored flag values of the current context.
     *
     * @param flagKey A list of flag keys which were created, updated, or deleted as part of the update.
     *                This list may be empty if the update resulted in no changed flag values.
     */
    void onChange(List<String> flagKey);

}
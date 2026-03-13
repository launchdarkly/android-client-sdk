package com.launchdarkly.sdk.android;

import androidx.annotation.NonNull;

import com.launchdarkly.sdk.fdv2.Selector;
import com.launchdarkly.sdk.android.subsystems.TransactionalDataStore;

/**
 * Adapts a {@link TransactionalDataStore} to the {@link SelectorSource} interface.
 * <p>
 * Analogous to {@code SelectorSourceFacade} in the java-server SDK (java-core). Created
 * alongside the update sink in the component wiring; passed independently to FDv2 streaming
 * and polling implementations so they can read the current selector without coupling to the
 * update-sink interface.
 */
final class SelectorSourceFacade implements SelectorSource {
    private final TransactionalDataStore store;

    SelectorSourceFacade(@NonNull TransactionalDataStore store) {
        this.store = store;
    }

    @Override
    @NonNull
    public Selector getSelector() {
        return store.getSelector();
    }
}

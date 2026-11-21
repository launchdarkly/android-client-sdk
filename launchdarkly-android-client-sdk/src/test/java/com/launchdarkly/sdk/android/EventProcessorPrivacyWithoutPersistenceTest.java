package com.launchdarkly.sdk.android;

import com.launchdarkly.sdk.android.integrations.EventPersistence;

/** {@link EventProcessorPrivacyTest} with persistence off, which is what an application gets by default. */
public class EventProcessorPrivacyWithoutPersistenceTest extends EventProcessorPrivacyTest {
    @Override
    protected EventPersistence persistence() {
        return EventPersistence.DISABLED;
    }
}

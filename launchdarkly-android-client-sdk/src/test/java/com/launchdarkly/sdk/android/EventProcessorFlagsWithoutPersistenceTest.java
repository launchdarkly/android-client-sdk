package com.launchdarkly.sdk.android;

import com.launchdarkly.sdk.android.integrations.EventPersistence;

/** {@link EventProcessorFlagsTest} with persistence off, which is what an application gets by default. */
public class EventProcessorFlagsWithoutPersistenceTest extends EventProcessorFlagsTest {
    @Override
    protected EventPersistence persistence() {
        return EventPersistence.DISABLED;
    }
}

package com.launchdarkly.sdk.android;

import com.launchdarkly.sdk.android.integrations.EventPersistence;

/** {@link EventProcessorBufferingTest} with persistence off, which is what an application gets by default. */
public class EventProcessorBufferingWithoutPersistenceTest extends EventProcessorBufferingTest {
    @Override
    protected EventPersistence persistence() {
        return EventPersistence.DISABLED;
    }
}

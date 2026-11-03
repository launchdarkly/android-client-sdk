package com.launchdarkly.sdk.android;

import com.launchdarkly.sdk.android.integrations.EventPersistence;

/** {@link DirectEventProcessorTest} with persistence off, which is what an application gets by default. */
public class DirectEventProcessorWithoutPersistenceTest extends DirectEventProcessorTest {
    @Override
    protected EventPersistence persistence() {
        return EventPersistence.DISABLED;
    }
}

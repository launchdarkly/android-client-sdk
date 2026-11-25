package com.launchdarkly.sdk.android.integrations;

import com.launchdarkly.sdk.android.Components;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class EventProcessorBuilderTest {
    @Test
    public void eventDeliveryDelayDefaultsToTwoSeconds() {
        assertEquals(2_000, Components.sendEvents().eventDeliveryDelayMillis);
    }

    @Test
    public void eventDeliveryDelayIsKeptBetweenZeroAndAMinute() {
        EventProcessorBuilder builder = Components.sendEvents();
        assertEquals(0, builder.eventDeliveryDelayMillis(0).eventDeliveryDelayMillis);
        assertEquals(5_000, builder.eventDeliveryDelayMillis(5_000).eventDeliveryDelayMillis);
        assertEquals(0, builder.eventDeliveryDelayMillis(-1).eventDeliveryDelayMillis);
        assertEquals(60_000, builder.eventDeliveryDelayMillis(61_000).eventDeliveryDelayMillis);
        assertEquals(60_000, builder.eventDeliveryDelayMillis(Long.MAX_VALUE).eventDeliveryDelayMillis);
    }
}

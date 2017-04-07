package com.launchdarkly.android;

import android.support.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.*;

@RunWith(AndroidJUnit4.class)
public class LDConfigTest {

    @Test
    public void TestBuilderDefaults(){
        LDConfig config = new LDConfig.Builder().build();
        assertTrue(config.isStream());
        assertFalse(config.isOffline());

        assertEquals(LDConfig.DEFAULT_BASE_URI, config.getBaseUri());
        assertEquals(LDConfig.DEFAULT_EVENTS_URI, config.getEventsUri());
        assertEquals(LDConfig.DEFAULT_STREAM_URI, config.getStreamUri());

        assertEquals(LDConfig.DEFAULT_CONNECTION_TIMEOUT_MILLIS, config.getConnectionTimeoutMillis());
        assertEquals(LDConfig.DEFAULT_EVENTS_CAPACITY, config.getEventsCapacity());
        assertEquals(LDConfig.DEFAULT_FLUSH_INTERVAL_MILLIS, config.getEventsFlushIntervalMillis());
        assertEquals(LDConfig.DEFAULT_POLLING_INTERVAL_MILLIS, config.getPollingIntervalMillis());

        assertEquals(LDConfig.DEFAULT_BACKGROUND_POLLING_INTERVAL_MILLIS, config.getBackgroundPollingIntervalMillis());
        assertEquals(false, config.isDisableBackgroundPolling());

        assertEquals(null, config.getMobileKey());
    }


    @Test
    public void TestBuilderStreamDisabled() {
        LDConfig config = new LDConfig.Builder()
                .setStream(false)
                .build();

        assertFalse(config.isStream());
        assertFalse(config.isOffline());
        assertEquals(LDConfig.DEFAULT_POLLING_INTERVAL_MILLIS, config.getPollingIntervalMillis());
        assertEquals(LDConfig.DEFAULT_POLLING_INTERVAL_MILLIS, PollingUpdater.backgroundPollingIntervalMillis);
        assertEquals(LDConfig.DEFAULT_POLLING_INTERVAL_MILLIS, config.getEventsFlushIntervalMillis());
    }

    @Test
    public void TestBuilderStreamDisabledCustomIntervals() {
        LDConfig config = new LDConfig.Builder()
                .setStream(false)
                .setPollingIntervalMillis(LDConfig.DEFAULT_POLLING_INTERVAL_MILLIS + 1)
                .setBackgroundPollingIntervalMillis(LDConfig.DEFAULT_BACKGROUND_POLLING_INTERVAL_MILLIS + 1)
                .build();

        assertFalse(config.isStream());
        assertFalse(config.isOffline());
        assertEquals(LDConfig.DEFAULT_POLLING_INTERVAL_MILLIS + 1, config.getPollingIntervalMillis());
        assertEquals(LDConfig.DEFAULT_POLLING_INTERVAL_MILLIS + 1, PollingUpdater.backgroundPollingIntervalMillis);
        assertEquals(LDConfig.DEFAULT_POLLING_INTERVAL_MILLIS + 1, config.getEventsFlushIntervalMillis());
    }

    @Test
    public void TestBuilderStreamDisabledBackgroundUpdatingDisabled() {
        LDConfig config = new LDConfig.Builder()
                .setStream(false)
                .setDisableBackgroundUpdating(true)
                .build();

        assertFalse(config.isStream());
        assertFalse(config.isOffline());
        assertTrue(config.isDisableBackgroundPolling());
        assertEquals(LDConfig.DEFAULT_POLLING_INTERVAL_MILLIS, config.getPollingIntervalMillis());
        assertEquals(LDConfig.DEFAULT_POLLING_INTERVAL_MILLIS, config.getEventsFlushIntervalMillis());
    }

    @Test
    public void TestBuilderStreamDisabledPollingIntervalBelowMinimum() {
        LDConfig config = new LDConfig.Builder()
                .setStream(false)
                .setPollingIntervalMillis(LDConfig.MIN_POLLING_INTERVAL_MILLIS - 1)
                .build();

        assertFalse(config.isStream());
        assertFalse(config.isOffline());
        assertFalse(config.isDisableBackgroundPolling());
        assertEquals(LDConfig.MIN_POLLING_INTERVAL_MILLIS, config.getPollingIntervalMillis());
        assertEquals(LDConfig.DEFAULT_BACKGROUND_POLLING_INTERVAL_MILLIS, PollingUpdater.backgroundPollingIntervalMillis);
        assertEquals(LDConfig.MIN_POLLING_INTERVAL_MILLIS, config.getEventsFlushIntervalMillis());
    }

    @Test
    public void TestBuilderStreamDisabledBackgroundPollingIntervalBelowMinimum() {
        LDConfig config = new LDConfig.Builder()
                .setStream(false)
                .setBackgroundPollingIntervalMillis(LDConfig.MIN_POLLING_INTERVAL_MILLIS - 1)
                .build();

        assertFalse(config.isStream());
        assertFalse(config.isOffline());
        assertFalse(config.isDisableBackgroundPolling());
        assertEquals(LDConfig.DEFAULT_POLLING_INTERVAL_MILLIS, config.getPollingIntervalMillis());
        assertEquals(LDConfig.DEFAULT_POLLING_INTERVAL_MILLIS, PollingUpdater.backgroundPollingIntervalMillis);
        assertEquals(LDConfig.DEFAULT_POLLING_INTERVAL_MILLIS, config.getEventsFlushIntervalMillis());
    }
}
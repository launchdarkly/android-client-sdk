package com.launchdarkly.sdk.android.integrations;

import static org.easymock.EasyMock.createMock;
import static org.easymock.EasyMock.eq;
import static org.easymock.EasyMock.expect;
import static org.easymock.EasyMock.replay;
import static org.easymock.EasyMock.verify;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import com.launchdarkly.sdk.android.LDClientInterface;
import com.launchdarkly.sdk.android.LaunchDarklyException;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class EventFlushingCrashHandlerTest {
    private final List<String> calls = new ArrayList<>();
    private final Thread crashed = new Thread("crashed");
    private final Throwable crash = new RuntimeException("boom");
    private final Thread.UncaughtExceptionHandler next = (thread, throwable) -> {
        assertSame(crashed, thread);
        assertSame(crash, throwable);
        calls.add("next");
    };

    private Thread.UncaughtExceptionHandler originalDefault;

    @Before
    public void saveDefaultHandler() {
        originalDefault = Thread.getDefaultUncaughtExceptionHandler();
    }

    @After
    public void restoreDefaultHandler() {
        Thread.setDefaultUncaughtExceptionHandler(originalDefault);
    }

    @Test
    public void deliversTheEventsBeforePassingTheCrashOn() {
        LDClientInterface client = createMock(LDClientInterface.class);
        expect(client.flushAndWait(eq(2000L), eq(TimeUnit.MILLISECONDS))).andAnswer(() -> {
            calls.add("flush");
            return true;
        });
        replay(client);

        new EventFlushingCrashHandler(() -> client, 2000, next).uncaughtException(crashed, crash);

        verify(client);
        assertEquals(Arrays.asList("flush", "next"), calls);
    }

    @Test
    public void passesTheCrashOnWhenTheEventsAreNotDelivered() {
        LDClientInterface client = createMock(LDClientInterface.class);
        expect(client.flushAndWait(eq(2000L), eq(TimeUnit.MILLISECONDS))).andReturn(false);
        replay(client);

        new EventFlushingCrashHandler(() -> client, 2000, next).uncaughtException(crashed, crash);

        verify(client);
        assertEquals(Arrays.asList("next"), calls);
    }

    @Test
    public void passesTheCrashOnWhenTheClientWasNeverInitialized() {
        new EventFlushingCrashHandler(() -> {
            throw new LaunchDarklyException("LDClient.get() was called before init()!");
        }, 2000, next).uncaughtException(crashed, crash);

        assertEquals(Arrays.asList("next"), calls);
    }

    @Test
    public void passesTheCrashOnWhenTheFlushThrows() {
        LDClientInterface client = createMock(LDClientInterface.class);
        expect(client.flushAndWait(eq(2000L), eq(TimeUnit.MILLISECONDS)))
                .andThrow(new IllegalStateException("the crash broke the client too"));
        replay(client);

        new EventFlushingCrashHandler(() -> client, 2000, next).uncaughtException(crashed, crash);

        assertEquals(Arrays.asList("next"), calls);
    }

    @Test
    public void installsInFrontOfTheCurrentDefaultHandler() {
        Thread.setDefaultUncaughtExceptionHandler(next);

        EventFlushingCrashHandler.install(2, TimeUnit.SECONDS);
        Thread.UncaughtExceptionHandler installed = Thread.getDefaultUncaughtExceptionHandler();
        installed.uncaughtException(crashed, crash);

        assertTrue(installed instanceof EventFlushingCrashHandler);
        assertEquals(Arrays.asList("next"), calls);
    }

    @Test
    public void installingTwiceKeepsTheFirstHandler() {
        Thread.setDefaultUncaughtExceptionHandler(next);

        EventFlushingCrashHandler.install(2, TimeUnit.SECONDS);
        Thread.UncaughtExceptionHandler first = Thread.getDefaultUncaughtExceptionHandler();
        EventFlushingCrashHandler.install(5, TimeUnit.SECONDS);

        assertSame(first, Thread.getDefaultUncaughtExceptionHandler());
        first.uncaughtException(crashed, crash);
        assertEquals(Arrays.asList("next"), calls);
    }
}

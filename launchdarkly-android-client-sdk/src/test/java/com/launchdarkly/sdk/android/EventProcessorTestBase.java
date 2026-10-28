package com.launchdarkly.sdk.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.android.LDConfig.Builder.AutoEnvAttributes;
import com.launchdarkly.sdk.android.env.EnvironmentReporterBuilder;
import com.launchdarkly.sdk.android.env.IEnvironmentReporter;
import com.launchdarkly.sdk.android.integrations.EventPersistence;
import com.launchdarkly.sdk.android.integrations.EventProcessorBuilder;
import com.launchdarkly.sdk.android.subsystems.ClientContext;
import com.launchdarkly.sdk.android.subsystems.EventProcessor;
import com.launchdarkly.testhelpers.httptest.Handlers;
import com.launchdarkly.testhelpers.httptest.HttpServer;
import com.launchdarkly.testhelpers.httptest.RequestInfo;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import org.junit.rules.Timeout;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Fixture for tests that drive the SDK's event processor directly and read back the analytics
 * payload it posts.
 */
public abstract class EventProcessorTestBase {
    protected static final String MOBILE_KEY = "test-mobile-key";
    protected static final LDContext CONTEXT = LDContext.create("user-key");

    // Long enough that the only payload in a test is the one it asks for explicitly.
    protected static final int NO_PERIODIC_FLUSH_MILLIS = 600_000;

    @Rule
    public Timeout globalTimeout = Timeout.seconds(60);
    @Rule
    public LogCaptureRule logging = new LogCaptureRule();
    /**
     * A directory of its own per test, so that the events one test persists are never found by the next.
     */
    @Rule
    public TemporaryFolder eventsDirectory = new TemporaryFolder();

    private final IEnvironmentReporter environmentReporter = new EnvironmentReporterBuilder().build();

    /**
     * The processor persists events, so it needs somewhere to put them. Tests get a real directory
     * rather than a stub, which is what makes them exercise the same path an application does.
     */
    protected MockPlatformState platformState() {
        MockPlatformState platformState = new MockPlatformState();
        platformState.setNoBackupFilesDir(eventsDirectory.getRoot());
        return platformState;
    }

    protected HttpServer startEventsServer() {
        return HttpServer.start(Handlers.status(202));
    }

    protected EventProcessor makeEventProcessor(HttpServer server, int capacity) {
        return makeEventProcessor(server, capacity, true);
    }

    protected EventProcessor makeEventProcessor(HttpServer server, int capacity,
                                                boolean diagnosticOptOut) {
        return makeEventProcessor(server, eventsBuilder(capacity), diagnosticOptOut);
    }

    /**
     * @return an events builder with the capacity set, periodic flushing effectively off, and no waiting
     *   on flag requests, for tests that need to configure something else on top such as private attributes
     */
    protected EventProcessorBuilder eventsBuilder(int capacity) {
        return ((ComponentsImpl.EventProcessorBuilderImpl) Components.sendEvents()
                .capacity(capacity)
                .eventPersistence(persistence())
                .flushIntervalMillis(NO_PERIODIC_FLUSH_MILLIS))
                .flagTimingsForTesting(FlagTraffic.Timings.NONE);
    }

    /**
     * {@link #eventsBuilder} without the backoff, so every flush posts and is retried at once, for tests
     * about what a failure keeps rather than about how long it waits.
     */
    protected EventProcessorBuilder eventsBuilderWithoutBackoff(int capacity) {
        return ((ComponentsImpl.EventProcessorBuilderImpl) eventsBuilder(capacity))
                .retryDelaysForTesting(0, 0, 0);
    }

    /**
     * The persistence {@link #eventsBuilder} configures. IMMEDIATE here, which is what lets a test
     * assert on the store straight after a track; each suite also runs as a subclass that answers
     * DISABLED, because that is what an application gets unless it asks.
     */
    protected EventPersistence persistence() {
        return EventPersistence.IMMEDIATE;
    }

    /** Skips a test whose subject is the disk, in the subclasses that run a suite without one. */
    protected void assumePersisting() {
        Assume.assumeTrue("needs a disk", persistence() != EventPersistence.DISABLED);
    }

    protected EventProcessor makeEventProcessor(HttpServer server, EventProcessorBuilder events,
                                                boolean diagnosticOptOut) {
        return makeEventProcessor(server.getUri(), events, diagnosticOptOut);
    }

    protected EventProcessor makeEventProcessor(URI eventsUri, EventProcessorBuilder events,
                                                boolean diagnosticOptOut) {
        EventProcessor eventProcessor = buildOfflineEventProcessor(eventsUri, events, diagnosticOptOut,
                platformState());
        // LDClient turns the processor on once initialization decides the SDK is not in offline mode.
        eventProcessor.setOffline(false);
        return eventProcessor;
    }

    /** @return the processor as the SDK builds it, before initialization has turned it on */
    protected EventProcessor buildOfflineEventProcessor(HttpServer server, EventProcessorBuilder events,
                                                        boolean diagnosticOptOut) {
        return buildOfflineEventProcessor(server, events, diagnosticOptOut, platformState());
    }

    protected EventProcessor buildOfflineEventProcessor(HttpServer server, EventProcessorBuilder events,
                                                        boolean diagnosticOptOut,
                                                        PlatformState platformState) {
        return buildOfflineEventProcessor(server.getUri(), events, diagnosticOptOut, platformState);
    }

    protected EventProcessor buildOfflineEventProcessor(URI eventsUri, EventProcessorBuilder events,
                                                        boolean diagnosticOptOut,
                                                        PlatformState platformState) {
        LDConfig config = new LDConfig.Builder(AutoEnvAttributes.Disabled)
                .mobileKey(MOBILE_KEY)
                .diagnosticOptOut(diagnosticOptOut)
                .events(events)
                .serviceEndpoints(Components.serviceEndpoints().events(eventsUri))
                .build();
        ClientContext clientContext = ClientContextImpl.fromConfig(config, MOBILE_KEY, "",
                null, null, CONTEXT, logging.logger, platformState, environmentReporter, null);
        return config.events.build(clientContext);
    }

    /**
     * Flushes and returns every event the processor posted. {@code blockingFlush} does not return
     * until delivery completes, so there is no settling delay here.
     */
    protected List<LDValue> flushAndCollect(EventProcessor eventProcessor, HttpServer server) {
        eventProcessor.blockingFlush();
        return collectDelivered(server);
    }

    /** Returns every event posted so far, without asking for a flush. */
    protected List<LDValue> collectDelivered(HttpServer server) {
        List<LDValue> events = new ArrayList<>();
        collectRequest(server.getRecorder().requireRequest(10, TimeUnit.SECONDS), events);
        while (server.getRecorder().count() > 0) {
            collectRequest(server.getRecorder().requireRequest(10, TimeUnit.SECONDS), events);
        }
        return events;
    }

    private void collectRequest(RequestInfo request, List<LDValue> events) {
        assertEquals("POST", request.getMethod());
        assertEquals("/mobile/events/bulk", request.getPath());
        for (LDValue event : LDValue.parse(request.getBody()).values()) {
            events.add(event);
        }
    }

    protected int countEventsOfKind(List<LDValue> events, String kind) {
        int count = 0;
        for (LDValue event : events) {
            if (kind.equals(event.get("kind").stringValue())) {
                count++;
            }
        }
        return count;
    }

    protected LDValue requireEventOfKind(List<LDValue> events, String kind) {
        for (LDValue event : events) {
            if (kind.equals(event.get("kind").stringValue())) {
                return event;
            }
        }
        throw new AssertionError("no event of kind " + kind + " in " + events);
    }

    protected int summaryCountFor(List<LDValue> events, String flagKey) {
        int total = 0;
        boolean sawSummary = false;
        for (LDValue event : events) {
            if (!"summary".equals(event.get("kind").stringValue())) {
                continue;
            }
            sawSummary = true;
            // Per-context summarization means a flag can have more than one counter entry.
            for (LDValue counter : event.get("features").get(flagKey).get("counters").values()) {
                total += counter.get("count").intValue();
            }
        }
        assertTrue("no summary event was delivered", sawSummary);
        return total;
    }
}

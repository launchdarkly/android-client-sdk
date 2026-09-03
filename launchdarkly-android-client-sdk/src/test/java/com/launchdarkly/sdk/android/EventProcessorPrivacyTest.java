package com.launchdarkly.sdk.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.launchdarkly.sdk.ContextKind;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.android.subsystems.EventProcessor;
import com.launchdarkly.testhelpers.httptest.HttpServer;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Context attribute redaction, as observed in the payload the processor actually posts.
 * <p>
 * Redaction is not reimplemented by the SDK's event processor - it reuses java-sdk-internal's
 * context formatter through {@link com.launchdarkly.sdk.internal.events.AndroidEventBuffer}. These
 * tests exist because that reuse is the whole argument for the current design, and nothing else in
 * this repository asserts that an attribute an application marked private stays out of the wire
 * format. Beyond the redaction itself they pin the two rules that are easy to get wrong when
 * events are assembled somewhere new: which events inline a context at all, and which of them
 * additionally redact the attributes of an anonymous context.
 */
public class EventProcessorPrivacyTest extends EventProcessorTestBase {
    private static final String FLAG_KEY = "flag-key";
    private static final int DEFAULT_CAPACITY = 100;

    private static final LDContext PERSON = LDContext.builder("user-key")
            .name("Sandy")
            .set("email", "sandy@example.com")
            .set("address", LDValue.buildObject()
                    .put("city", "Oakland")
                    .put("street", "123 Main St")
                    .build())
            .build();

    @Test
    public void namedPrivateAttributesAreRedactedAndReported() throws Exception {
        try (HttpServer server = startEventsServer()) {
            EventProcessor eventProcessor = makeEventProcessor(server,
                    eventsBuilder(DEFAULT_CAPACITY).privateAttributes("email"), true);
            try {
                eventProcessor.recordIdentifyEvent(PERSON);

                LDValue context = requireEventOfKind(flushAndCollect(eventProcessor, server),
                        "identify").get("context");

                assertEquals(LDValue.of("user-key"), context.get("key"));
                assertEquals("email must not appear in the payload",
                        LDValue.ofNull(), context.get("email"));
                // Attributes that were not marked private are still sent.
                assertEquals(LDValue.of("Sandy"), context.get("name"));
                assertEquals(redacted("email"), redactedAttributes(context));
            } finally {
                eventProcessor.close();
            }
        }
    }

    @Test
    public void allAttributesPrivateLeavesOnlyTheKey() throws Exception {
        try (HttpServer server = startEventsServer()) {
            EventProcessor eventProcessor = makeEventProcessor(server,
                    eventsBuilder(DEFAULT_CAPACITY).allAttributesPrivate(true), true);
            try {
                eventProcessor.recordIdentifyEvent(PERSON);

                LDValue context = requireEventOfKind(flushAndCollect(eventProcessor, server),
                        "identify").get("context");

                // The key and kind are identifiers rather than attributes, so they survive.
                assertEquals(LDValue.of("user-key"), context.get("key"));
                assertEquals(LDValue.of("user"), context.get("kind"));
                assertEquals(LDValue.ofNull(), context.get("name"));
                assertEquals(LDValue.ofNull(), context.get("email"));
                assertEquals(LDValue.ofNull(), context.get("address"));
                assertEquals(redacted("address", "email", "name"), redactedAttributes(context));
            } finally {
                eventProcessor.close();
            }
        }
    }

    @Test
    public void aPrivateSubAttributeRedactsOnlyThatProperty() throws Exception {
        try (HttpServer server = startEventsServer()) {
            EventProcessor eventProcessor = makeEventProcessor(server,
                    eventsBuilder(DEFAULT_CAPACITY).privateAttributes("/address/street"), true);
            try {
                eventProcessor.recordIdentifyEvent(PERSON);

                LDValue context = requireEventOfKind(flushAndCollect(eventProcessor, server),
                        "identify").get("context");

                assertEquals(LDValue.of("Oakland"), context.get("address").get("city"));
                assertEquals(LDValue.ofNull(), context.get("address").get("street"));
                assertEquals(redacted("/address/street"), redactedAttributes(context));
            } finally {
                eventProcessor.close();
            }
        }
    }

    @Test
    public void attributesMarkedPrivateOnTheContextItselfAreRedacted() throws Exception {
        // An application can mark an attribute private per-context instead of globally; the
        // processor is not configured with anything in this case.
        LDContext context = LDContext.builder("user-key")
                .name("Sandy")
                .set("email", "sandy@example.com")
                .privateAttributes("email")
                .build();
        try (HttpServer server = startEventsServer()) {
            EventProcessor eventProcessor = makeEventProcessor(server, DEFAULT_CAPACITY);
            try {
                eventProcessor.recordIdentifyEvent(context);

                LDValue delivered = requireEventOfKind(flushAndCollect(eventProcessor, server),
                        "identify").get("context");

                assertEquals(LDValue.ofNull(), delivered.get("email"));
                assertEquals(LDValue.of("Sandy"), delivered.get("name"));
                assertEquals(redacted("email"), redactedAttributes(delivered));
            } finally {
                eventProcessor.close();
            }
        }
    }

    @Test
    public void anonymousContextAttributesAreRedactedFromFeatureEventsButNotIdentifyEvents() throws Exception {
        LDContext anonymous = LDContext.builder(ContextKind.DEFAULT, "anon-key")
                .anonymous(true)
                .set("email", "sandy@example.com")
                .build();
        try (HttpServer server = startEventsServer()) {
            EventProcessor eventProcessor = makeEventProcessor(server, DEFAULT_CAPACITY);
            try {
                eventProcessor.recordIdentifyEvent(anonymous);
                eventProcessor.recordEvaluationEvent(anonymous, FLAG_KEY, 10, 1, LDValue.of(true),
                        null, LDValue.of(false), true, null);

                List<LDValue> events = flushAndCollect(eventProcessor, server);

                // An identify event is the application deliberately registering this context.
                LDValue identifyContext = requireEventOfKind(events, "identify").get("context");
                assertEquals(LDValue.of("sandy@example.com"), identifyContext.get("email"));

                // A feature event redacts every attribute of an anonymous context, without the
                // application having to mark anything private.
                LDValue featureContext = requireEventOfKind(events, "feature").get("context");
                assertEquals(LDValue.of("anon-key"), featureContext.get("key"));
                assertEquals(LDValue.ofNull(), featureContext.get("email"));
                assertEquals(redacted("email"), redactedAttributes(featureContext));
            } finally {
                eventProcessor.close();
            }
        }
    }

    @Test
    public void summaryEventsRedactPrivateAttributesOfTheContextTheyInline() throws Exception {
        // Because this SDK summarizes per context, a summary event inlines the context it counted
        // rather than just its key, and so it is subject to redaction like any other event. This
        // is the case most easily missed, since summaries are produced by the buffer rather than
        // by an explicit record call.
        try (HttpServer server = startEventsServer()) {
            EventProcessor eventProcessor = makeEventProcessor(server,
                    eventsBuilder(DEFAULT_CAPACITY).privateAttributes("email"), true);
            try {
                eventProcessor.recordEvaluationEvent(PERSON, FLAG_KEY, 10, 1, LDValue.of(true),
                        null, LDValue.of(false), false, null);

                LDValue summary = requireEventOfKind(flushAndCollect(eventProcessor, server),
                        "summary");

                LDValue context = summary.get("context");
                assertEquals(LDValue.of("user-key"), context.get("key"));
                assertEquals(LDValue.ofNull(), context.get("email"));
                assertEquals(redacted("email"), redactedAttributes(context));
                // Attributes that were not marked private are inlined here, same as anywhere else.
                assertEquals(LDValue.of("Oakland"), context.get("address").get("city"));
                assertFalse("summary should not contain the email value anywhere",
                        summary.toJsonString().contains("sandy@example.com"));
            } finally {
                eventProcessor.close();
            }
        }
    }

    @Test
    public void redactionAppliesToEveryKindOfAMultiContext() throws Exception {
        LDContext multi = LDContext.createMulti(
                LDContext.builder(ContextKind.of("user"), "user-key")
                        .set("email", "sandy@example.com")
                        .build(),
                LDContext.builder(ContextKind.of("org"), "org-key")
                        .set("email", "billing@example.com")
                        .build());
        try (HttpServer server = startEventsServer()) {
            EventProcessor eventProcessor = makeEventProcessor(server,
                    eventsBuilder(DEFAULT_CAPACITY).privateAttributes("email"), true);
            try {
                eventProcessor.recordIdentifyEvent(multi);

                LDValue delivered = requireEventOfKind(flushAndCollect(eventProcessor, server),
                        "identify").get("context");

                assertEquals(LDValue.of("multi"), delivered.get("kind"));
                assertEquals(LDValue.ofNull(), delivered.get("user").get("email"));
                assertEquals(LDValue.ofNull(), delivered.get("org").get("email"));
                assertEquals(redacted("email"), redactedAttributes(delivered.get("user")));
                assertEquals(redacted("email"), redactedAttributes(delivered.get("org")));
            } finally {
                eventProcessor.close();
            }
        }
    }

    @Test
    public void noPayloadContainsAPrivateValueAnywhere() throws Exception {
        // A belt-and-braces check over the whole request body rather than one field, so that a
        // private value cannot slip through in some event kind these tests do not name.
        try (HttpServer server = startEventsServer()) {
            EventProcessor eventProcessor = makeEventProcessor(server,
                    eventsBuilder(DEFAULT_CAPACITY).privateAttributes("email"), true);
            try {
                eventProcessor.recordIdentifyEvent(PERSON);
                eventProcessor.recordCustomEvent(PERSON, "an-event", LDValue.ofNull(), null);
                eventProcessor.recordEvaluationEvent(PERSON, FLAG_KEY, 10, 1, LDValue.of(true),
                        null, LDValue.of(false), true, null);

                List<LDValue> events = flushAndCollect(eventProcessor, server);

                assertTrue("expected identify, custom, feature and summary events",
                        events.size() >= 4);
                for (LDValue event : events) {
                    assertFalse("private value leaked into " + event.get("kind") + ": " + event,
                            event.toJsonString().contains("sandy@example.com"));
                }
            } finally {
                eventProcessor.close();
            }
        }
    }

    private static List<String> redacted(String... names) {
        List<String> result = new ArrayList<>();
        for (String name : names) {
            result.add(name);
        }
        return result;
    }

    /** The names the payload itself reports as redacted, sorted so the comparison is stable. */
    private static List<String> redactedAttributes(LDValue context) {
        List<String> names = new ArrayList<>();
        for (LDValue name : context.get("_meta").get("redactedAttributes").values()) {
            names.add(name.stringValue());
        }
        java.util.Collections.sort(names);
        return names;
    }
}

package com.launchdarkly.sdk.android;

import com.launchdarkly.sdk.LDContext;

/**
 * Caches the encoded JSON of the contexts events were most recently written for.
 * <p>
 * The access pattern this exploits: an application identifies once and then evaluates repeatedly, so a
 * run of events shares one context. Encoding it is roughly half of what writing an event costs, and doing
 * it once per run rather than once per event is most of that half removed.
 * <p>
 * <b>Two entries rather than one, because the event stream interleaves two encodings of the same
 * context.</b> Feature events redact the attributes of an anonymous context; custom, identify and debug
 * events do not. The two produce different JSON for an anonymous context, so a single entry thrashes
 * between them -- measured on the Apple SDK at zero hits in 40,400 lookups. The directive is a boolean,
 * so two slots cover the interleaving exactly.
 * <p>
 * <b>The key is the whole of the correctness argument.</b> Serving one context's redacted JSON for
 * another is a privacy bug, and a far worse outcome than being slow, so the key is the context
 * <i>value</i> rather than a digest of it. Everything the encoding depends on is either compared by
 * {@link LDContext#equals} -- kind, key, name, anonymous, attributes, the sub-contexts of a
 * multi-context, and the private attribute references -- or fixed for the lifetime of the buffer that
 * owns this cache, which is where {@code allAttributesPrivate} and the global private attributes come
 * from.
 * <p>
 * The Apple SDK needs a further check of how each private attribute was spelled, because its reference
 * type compares parsed components while redaction writes the raw spelling. Neither half holds here:
 * {@code AttributeRef.equals} compares the raw path, so two spellings are unequal and take separate
 * slots, and {@link EventContextWriter} writes the attribute's own name rather than the spelling, so
 * they encode alike in any case. {@code OutboundEventBufferSerializationTest} pins both.
 */
final class ContextEncodingCache {
    private final LDContext[] contexts = new LDContext[2];
    private final String[] encodings = new String[2];

    private long hits;
    private long misses;

    /**
     * @param context the context an event is being written for
     * @param redactAnonymous whether this event redacts the attributes of an anonymous context
     * @return the encoded context, or null if this slot does not hold it
     */
    synchronized String get(LDContext context, boolean redactAnonymous) {
        int slot = slot(redactAnonymous);
        // LDContext.equals short-circuits on identity, which is the common case: an application hands
        // the same context to every evaluation, so a hit costs a reference comparison, not a walk.
        if (contexts[slot] != null && contexts[slot].equals(context)) {
            hits++;
            return encodings[slot];
        }
        misses++;
        return null;
    }

    synchronized void put(LDContext context, boolean redactAnonymous, String encoded) {
        int slot = slot(redactAnonymous);
        contexts[slot] = context;
        encodings[slot] = encoded;
    }

    /** Counted only so a benchmark can report a hit rate rather than assume one. */
    synchronized long getHits() {
        return hits;
    }

    synchronized long getMisses() {
        return misses;
    }

    /**
     * The redaction directive belongs to the event rather than to the context, so it is not part of
     * {@link LDContext#equals}; without a slot of its own the two encodings would evict each other.
     */
    private static int slot(boolean redactAnonymous) {
        return redactAnonymous ? 1 : 0;
    }
}

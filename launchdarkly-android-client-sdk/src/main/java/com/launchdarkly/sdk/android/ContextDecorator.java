package com.launchdarkly.sdk.android;

import androidx.annotation.NonNull;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.ContextKind;
import com.launchdarkly.sdk.ContextMultiBuilder;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.android.subsystems.PersistentDataStore;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

final class ContextDecorator {
    private static final String GENERATED_KEY_SHARED_PREFS_PREFIX = "anon-key-";

    private final boolean generateAnonymousKeys;

    private Map<ContextKind, String> cachedGeneratedKey = new HashMap<>();
    private PersistentDataStore store = null;
    private Object generatedKeyLock = new Object();

    public ContextDecorator(
            @NonNull PersistentDataStore store,
            boolean generateAnonymousKeys
    ) {
        this.store = store;
        this.generateAnonymousKeys = generateAnonymousKeys;
    }

    public LDContext decorateContext(LDContext context, LDLogger logger) {
        if (!generateAnonymousKeys) {
            return context;
        }
        if (context.isMultiple()) {
            boolean hasAnyAnon = false;
            for (int i = 0; i < context.getIndividualContextCount(); i++) {
                if (context.getIndividualContext(i).isAnonymous()) {
                    hasAnyAnon = true;
                    break;
                }
            }
            if (hasAnyAnon) {
                ContextMultiBuilder builder = LDContext.multiBuilder();
                for (int i = 0; i < context.getIndividualContextCount(); i++) {
                    LDContext c = context.getIndividualContext(i);
                    builder.add(c.isAnonymous() ?  singleKindContextWithGeneratedKey(c, logger) : c);
                }
                return builder.build();
            }
        } else if (context.isAnonymous()) {
            return singleKindContextWithGeneratedKey(context, logger);
        }
        return context;
    }

    private LDContext singleKindContextWithGeneratedKey(LDContext context, LDLogger logger) {
        return LDContext.builderFromContext(context)
                .key(getOrCreateAutoContextKey(context.getKind(), logger))
                .build();
    }

    private String getOrCreateAutoContextKey(ContextKind contextKind, LDLogger logger) {
        synchronized (generatedKeyLock) {
            String key = cachedGeneratedKey.get(contextKind);
            if (key != null) {
                return key;
            }
            final String storeNamespace = LDConfig.SHARED_PREFS_BASE_KEY + "id";
            final String storeKey = GENERATED_KEY_SHARED_PREFS_PREFIX + contextKind.toString();
            key = store.getValue(storeNamespace, storeKey);
            if (key != null) {
                cachedGeneratedKey.put(contextKind, key);
                return key;
            }
            final String generatedKey = UUID.randomUUID().toString();
            cachedGeneratedKey.put(contextKind, generatedKey);

            logger.info(
                    "Did not find a generated anonymous key for context kind \"{}\". Generating a new one: {}",
                    contextKind, generatedKey);

            // Updating persistent storage may be a blocking I/O call, so don't do it on the main
            // thread. That part doesn't need to be done under this lock anyway - the fact that
            // we've put it into the cachedGeneratedKey map already means any subsequent calls will
            // get that value and not have to hit the persistent store.
            new Thread(new Runnable() {
                public void run() {
                    store.setValue(storeNamespace, storeKey, generatedKey);
                }
            }).run();

            return generatedKey;
        }
    }
}

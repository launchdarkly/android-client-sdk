package com.launchdarkly.sdk.android;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.ContextKind;
import com.launchdarkly.sdk.ContextMultiBuilder;
import com.launchdarkly.sdk.LDContext;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

final class ContextDecorator {
    private static final String GENERATED_KEY_SHARED_PREFS_PREFIX = "anon-key-";

    private final Application application;
    private final boolean generateAnonymousKeys;

    private Map<ContextKind, String> cachedGeneratedKey = new HashMap<>();
    private SharedPreferences generatedKeysSharedPreferences = null;
    private Object generatedKeyLock = new Object();

    public ContextDecorator(
            Application application,
            boolean generateAnonymousKeys
    ) {
        this.application = application;
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
        } else if (!context.isAnonymous()) {
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
            final String sharedPrefsKey = GENERATED_KEY_SHARED_PREFS_PREFIX + contextKind.toString();
            if (generatedKeysSharedPreferences == null) {
                generatedKeysSharedPreferences = application.getSharedPreferences(
                        LDConfig.SHARED_PREFS_BASE_KEY + "id", Context.MODE_PRIVATE);
            }
            key = generatedKeysSharedPreferences.getString(sharedPrefsKey, null);
            if (key != null) {
                cachedGeneratedKey.put(contextKind, key);
                return key;
            }
            final String generatedKey = UUID.randomUUID().toString();
            cachedGeneratedKey.put(contextKind, generatedKey);

            logger.info(
                    "Did not find a generated anonymous key for context kind \"{}\". Generating a new one: {}",
                    contextKind, generatedKey);

            // Editing SharedPreferences is a blocking I/O call, so don't do it on the main thread.
            // That part doesn't need to be done under this lock anyway - the fact that we've put it
            // into the cachedGeneratedKey map already means any subsequent calls will get that
            // value and not have to hit the preference store.
            new Thread(new Runnable() {
                public void run() {
                    SharedPreferences.Editor editor = generatedKeysSharedPreferences.edit();
                    editor.putString(sharedPrefsKey, generatedKey);
                    editor.apply();
                }
            }).run();

            return generatedKey;
        }
    }
}

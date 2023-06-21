package com.launchdarkly.sdk.android;

import androidx.annotation.NonNull;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.ContextMultiBuilder;
import com.launchdarkly.sdk.LDContext;

/**
 * A {@link IContextModifier} that will set the key of anonymous contexts to a randomly
 * generated one.  Generated keys are persisted and consistent for a given context kind
 * across calls to {@link #modifyContext(LDContext)}.
 */
final class AnonymousKeyContextModifier implements IContextModifier {

    @NonNull private final PersistentDataStoreWrapper persistentData;
    private final boolean generateAnonymousKeys;

    /**
     * @param persistentData that will be used for storing/retrieving keys
     * @param generateAnonymousKeys controls whether generated keys will be applied
     */
    public AnonymousKeyContextModifier(
            @NonNull PersistentDataStoreWrapper persistentData,
            boolean generateAnonymousKeys
    ) {
        this.persistentData = persistentData;
        this.generateAnonymousKeys = generateAnonymousKeys;
    }

    public LDContext modifyContext(LDContext context) {
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
                    builder.add(c.isAnonymous() ?  singleKindContextWithGeneratedKey(c) : c);
                }
                return builder.build();
            }
        } else if (context.isAnonymous()) {
            return singleKindContextWithGeneratedKey(context);
        }
        return context;
    }

    private LDContext singleKindContextWithGeneratedKey(LDContext context) {
        return LDContext.builderFromContext(context)
                .key(persistentData.getOrGenerateContextKey(context.getKind()))
                .build();
    }
}

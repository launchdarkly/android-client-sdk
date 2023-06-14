package com.launchdarkly.sdk.android;

import androidx.annotation.NonNull;

import com.launchdarkly.logging.LDLogger;
import com.launchdarkly.sdk.ContextMultiBuilder;
import com.launchdarkly.sdk.LDContext;

final class AnonymousKeyContextModifier {

    @NonNull private final PersistentDataStoreWrapper persistentData;
    private final boolean generateAnonymousKeys;

    public AnonymousKeyContextModifier(
            @NonNull PersistentDataStoreWrapper persistentData,
            boolean generateAnonymousKeys
    ) {
        this.persistentData = persistentData;
        this.generateAnonymousKeys = generateAnonymousKeys;
    }

    public LDContext modifyContext(LDContext context, LDLogger logger) {
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

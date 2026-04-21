package com.launchdarkly.sdk.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.android.subsystems.Callback;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Tests for {@link ContextDataManager} behavior that is not covered by the more specialized
 * test classes (flag data, apply, listeners on flag changes, etc.).
 */
public class ContextDataManagerTest extends ContextDataManagerTestBase {

    /**
     * FDv2 identify passes {@code skipCacheLoad=true} so cached flags are loaded by the data
     * source initializer instead of here. The context switch must still notify
     * {@link ContextDataManager.ContextSwitchListener} and complete the {@code switchToContext}
     * callback; otherwise ConnectivityManager never rebuilds the data source and identify hangs.
     */
    @Test
    public void switchToContextWithSkipCacheLoadStillNotifiesListenerAndCompletesCallback() {
        ContextDataManager manager = createDataManager();

        List<LDContext> contextsSeenByListener = new ArrayList<>();
        manager.setContextSwitchListener((context, view, onCompletion) -> {
            contextsSeenByListener.add(context);
            onCompletion.onSuccess(null);
        });
        assertEquals(
                "setContextSwitchListener should immediately notify with the current context",
                1,
                contextsSeenByListener.size());
        assertEquals(INITIAL_CONTEXT, contextsSeenByListener.get(0));

        final boolean[] switchCompletionCalled = {false};
        manager.switchToContext(CONTEXT, true, new Callback<Void>() {
            @Override
            public void onSuccess(Void result) {
                switchCompletionCalled[0] = true;
            }

            @Override
            public void onError(Throwable e) {
                fail("switchToContext completion should not error: " + e);
            }
        });

        assertEquals(
                "listener must be notified on context change even when skipCacheLoad is true (FDv2)",
                2,
                contextsSeenByListener.size());
        assertEquals(CONTEXT, contextsSeenByListener.get(1));
        assertTrue(
                "switchToContext completion must run after listener finishes (regression: early return skipped this)",
                switchCompletionCalled[0]);
    }
}

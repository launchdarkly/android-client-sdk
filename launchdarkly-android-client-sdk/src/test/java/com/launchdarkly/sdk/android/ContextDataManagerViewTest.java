package com.launchdarkly.sdk.android;

import static com.launchdarkly.sdk.android.AssertHelpers.assertFlagsEqual;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.annotation.NonNull;

import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.android.DataModel.Flag;
import com.launchdarkly.sdk.android.subsystems.Callback;
import com.launchdarkly.sdk.fdv2.ChangeSet;
import com.launchdarkly.sdk.fdv2.ChangeSetType;
import com.launchdarkly.sdk.fdv2.Selector;

import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

public class ContextDataManagerViewTest extends ContextDataManagerTestBase {

    private static final LDContext CONTEXT_A = LDContext.create("context-a");
    private static final LDContext CONTEXT_B = LDContext.create("context-b");

    private ContextDataManager.ContextDataManagerView captureView(ContextDataManager manager) {
        AtomicReference<ContextDataManager.ContextDataManagerView> ref = new AtomicReference<>();
        manager.setContextSwitchListener((context, view, onCompletion) -> {
            ref.set(view);
            onCompletion.onSuccess(null);
        });
        return ref.get();
    }

    @Test
    public void viewInitWritesDataWhenValid() {
        ContextDataManager manager = createDataManager();
        manager.switchToContext(CONTEXT, false, LDUtil.noOpCallback());
        ContextDataManager.ContextDataManagerView view = captureView(manager);

        Flag flag = new FlagBuilder("flag1").version(1).build();
        view.init(CONTEXT, Collections.singletonMap(flag.getKey(), flag));

        assertFlagsEqual(flag, manager.getNonDeletedFlag("flag1"));
    }

    @Test
    public void viewUpsertWritesDataWhenValid() {
        ContextDataManager manager = createDataManager();
        manager.switchToContext(CONTEXT, false, LDUtil.noOpCallback());
        ContextDataManager.ContextDataManagerView view = captureView(manager);

        Flag flag = new FlagBuilder("flag1").version(1).build();
        boolean result = view.upsert(CONTEXT, flag);

        assertTrue(result);
        assertFlagsEqual(flag, manager.getNonDeletedFlag("flag1"));
    }

    @Test
    public void viewApplyWritesDataWhenValid() {
        ContextDataManager manager = createDataManager();
        manager.switchToContext(CONTEXT, false, LDUtil.noOpCallback());
        ContextDataManager.ContextDataManagerView view = captureView(manager);

        Flag flag = new FlagBuilder("flag1").version(1).build();
        Map<String, Flag> items = Collections.singletonMap(flag.getKey(), flag);
        ChangeSet<Map<String, Flag>> changeSet = new ChangeSet<>(
                ChangeSetType.Full, Selector.EMPTY, items, null, false);
        view.apply(CONTEXT, changeSet);

        assertFlagsEqual(flag, manager.getNonDeletedFlag("flag1"));
    }

    @Test
    public void viewGetSelectorReturnsValueWhenValid() {
        ContextDataManager manager = createDataManager();
        manager.switchToContext(CONTEXT, false, LDUtil.noOpCallback());
        ContextDataManager.ContextDataManagerView view = captureView(manager);

        Selector selector = Selector.make(1, "state-1");
        Flag flag = new FlagBuilder("flag1").version(1).build();
        Map<String, Flag> items = Collections.singletonMap(flag.getKey(), flag);
        ChangeSet<Map<String, Flag>> changeSet = new ChangeSet<>(
                ChangeSetType.Full, selector, items, null, false);
        view.apply(CONTEXT, changeSet);

        assertEquals(1, view.getSelector().getVersion());
        assertEquals("state-1", view.getSelector().getState());
    }

    @Test
    public void invalidatedViewInitIsNoOp() {
        ContextDataManager manager = createDataManager();
        manager.switchToContext(CONTEXT, false, LDUtil.noOpCallback());
        ContextDataManager.ContextDataManagerView view = captureView(manager);

        view.invalidate();

        Flag flag = new FlagBuilder("flag1").version(1).build();
        view.init(CONTEXT, Collections.singletonMap(flag.getKey(), flag));

        assertNull(manager.getNonDeletedFlag("flag1"));
    }

    @Test
    public void invalidatedViewUpsertReturnsFalse() {
        ContextDataManager manager = createDataManager();
        manager.switchToContext(CONTEXT, false, LDUtil.noOpCallback());
        ContextDataManager.ContextDataManagerView view = captureView(manager);

        view.invalidate();

        Flag flag = new FlagBuilder("flag1").version(1).build();
        boolean result = view.upsert(CONTEXT, flag);

        assertFalse(result);
        assertNull(manager.getNonDeletedFlag("flag1"));
    }

    @Test
    public void invalidatedViewApplyIsNoOp() {
        ContextDataManager manager = createDataManager();
        manager.switchToContext(CONTEXT, false, LDUtil.noOpCallback());
        ContextDataManager.ContextDataManagerView view = captureView(manager);

        view.invalidate();

        Flag flag = new FlagBuilder("flag1").version(1).build();
        Map<String, Flag> items = Collections.singletonMap(flag.getKey(), flag);
        ChangeSet<Map<String, Flag>> changeSet = new ChangeSet<>(
                ChangeSetType.Full, Selector.EMPTY, items, null, false);
        view.apply(CONTEXT, changeSet);

        assertNull(manager.getNonDeletedFlag("flag1"));
    }

    @Test
    public void invalidatedViewGetSelectorReturnsEmpty() {
        ContextDataManager manager = createDataManager();
        manager.switchToContext(CONTEXT, false, LDUtil.noOpCallback());
        ContextDataManager.ContextDataManagerView view = captureView(manager);

        Selector selector = Selector.make(1, "state-1");
        Flag flag = new FlagBuilder("flag1").version(1).build();
        Map<String, Flag> items = Collections.singletonMap(flag.getKey(), flag);
        view.apply(CONTEXT, new ChangeSet<>(ChangeSetType.Full, selector, items, null, false));
        assertEquals(1, view.getSelector().getVersion());

        view.invalidate();

        assertTrue(view.getSelector().isEmpty());
    }

    @Test
    public void sameContextDoesNotInvalidateView() {
        ContextDataManager manager = createDataManager();
        manager.switchToContext(CONTEXT, false, LDUtil.noOpCallback());
        ContextDataManager.ContextDataManagerView view = captureView(manager);

        manager.switchToContext(CONTEXT, false, LDUtil.noOpCallback());

        Flag flag = new FlagBuilder("flag1").version(1).build();
        boolean result = view.upsert(CONTEXT, flag);
        assertTrue("View should still be valid after same-context switch", result);
    }

    @Test
    public void sameContextCallsOnCompletionImmediately() {
        ContextDataManager manager = createDataManager();
        manager.switchToContext(CONTEXT, false, LDUtil.noOpCallback());

        AtomicInteger completionCount = new AtomicInteger(0);
        manager.switchToContext(CONTEXT, false, new Callback<Void>() {
            @Override
            public void onSuccess(Void result) {
                completionCount.incrementAndGet();
            }

            @Override
            public void onError(Throwable error) {}
        });

        assertEquals(1, completionCount.get());
    }

    @Test
    public void differentContextInvalidatesOldView() {
        ContextDataManager manager = createDataManager();
        manager.switchToContext(CONTEXT_A, false, LDUtil.noOpCallback());
        ContextDataManager.ContextDataManagerView viewA = captureView(manager);

        manager.switchToContext(CONTEXT_B, false, LDUtil.noOpCallback());

        Flag flag = new FlagBuilder("flag1").version(1).build();
        assertFalse("Old view should be invalid", viewA.upsert(CONTEXT_A, flag));
        assertTrue("getSelector should return EMPTY for invalid view",
                viewA.getSelector().isEmpty());
    }

    @Test
    public void differentContextCreatesNewValidView() {
        ContextDataManager manager = createDataManager();
        manager.switchToContext(CONTEXT_A, false, LDUtil.noOpCallback());
        ContextDataManager.ContextDataManagerView viewA = captureView(manager);

        manager.switchToContext(CONTEXT_B, false, LDUtil.noOpCallback());
        ContextDataManager.ContextDataManagerView viewB = captureView(manager);

        assertNotSame(viewA, viewB);
        Flag flag = new FlagBuilder("flag1").version(1).build();
        assertTrue("New view should be valid", viewB.upsert(CONTEXT_B, flag));
    }

    @Test
    public void abaScenarioOldViewStaysInvalid() {
        ContextDataManager manager = createDataManager();
        manager.switchToContext(CONTEXT_A, false, LDUtil.noOpCallback());
        ContextDataManager.ContextDataManagerView viewA1 = captureView(manager);

        manager.switchToContext(CONTEXT_B, false, LDUtil.noOpCallback());
        manager.switchToContext(CONTEXT_A, false, LDUtil.noOpCallback());

        ContextDataManager.ContextDataManagerView viewA2 = captureView(manager);

        assertNotSame("Should be distinct view instances", viewA1, viewA2);
        assertFalse("Original A view should be invalid",
                viewA1.upsert(CONTEXT_A, new FlagBuilder("flag1").version(1).build()));
        assertTrue("New A view should be valid",
                viewA2.upsert(CONTEXT_A, new FlagBuilder("flag1").version(1).build()));
    }

    @Test
    public void onCompletionCalledWhenNoListenerSet() {
        ContextDataManager manager = createDataManager();

        AtomicInteger completionCount = new AtomicInteger(0);
        manager.switchToContext(CONTEXT, false, new Callback<Void>() {
            @Override
            public void onSuccess(Void result) {
                completionCount.incrementAndGet();
            }

            @Override
            public void onError(Throwable error) {}
        });

        assertEquals("Callback should be invoked even without a listener", 1, completionCount.get());
    }

    @Test
    public void onCompletionCalledExactlyOnceWithListener() {
        ContextDataManager manager = createDataManager();
        manager.setContextSwitchListener((context, view, onCompletion) -> onCompletion.onSuccess(null));

        AtomicInteger completionCount = new AtomicInteger(0);
        manager.switchToContext(CONTEXT, false, new Callback<Void>() {
            @Override
            public void onSuccess(Void result) {
                completionCount.incrementAndGet();
            }

            @Override
            public void onError(Throwable error) {}
        });

        assertEquals("Callback should be invoked exactly once", 1, completionCount.get());
    }

    @Test
    public void setListenerImmediatelyCallsOnContextChanged() {
        ContextDataManager manager = createDataManager();
        manager.switchToContext(CONTEXT, false, LDUtil.noOpCallback());

        AtomicReference<LDContext> receivedContext = new AtomicReference<>();
        AtomicReference<ContextDataManager.ContextDataManagerView> receivedView = new AtomicReference<>();

        manager.setContextSwitchListener((context, view, onCompletion) -> {
            receivedContext.set(context);
            receivedView.set(view);
            onCompletion.onSuccess(null);
        });

        assertEquals(CONTEXT, receivedContext.get());
        assertNotNull(receivedView.get());
        Flag flag = new FlagBuilder("flag1").version(1).build();
        assertTrue("View provided at registration should be valid",
                receivedView.get().upsert(CONTEXT, flag));
    }

    @Test
    public void removeListenerStopsNotifications() {
        ContextDataManager manager = createDataManager();
        AtomicInteger callCount = new AtomicInteger(0);
        manager.setContextSwitchListener((context, view, onCompletion) -> {
            callCount.incrementAndGet();
            onCompletion.onSuccess(null);
        });

        int countAfterRegistration = callCount.get();
        assertEquals("Should have been called once at registration", 1, countAfterRegistration);

        manager.removeContextSwitchListener();

        AtomicInteger completionCount = new AtomicInteger(0);
        manager.switchToContext(CONTEXT, false, new Callback<Void>() {
            @Override
            public void onSuccess(Void result) {
                completionCount.incrementAndGet();
            }

            @Override
            public void onError(Throwable error) {}
        });

        assertEquals("Listener should not have been called again", countAfterRegistration, callCount.get());
        assertEquals("Callback should still be invoked", 1, completionCount.get());
    }
}

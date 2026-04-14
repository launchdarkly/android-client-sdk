package com.launchdarkly.sdk.android.subsystems;

import java.io.Closeable;
import java.util.concurrent.Future;

/**
 * Interface for an FDv2 data source initializer that produces a single result.
 * <p>
 * An initializer runs once and delivers either a change set (success) or a status (error,
 * shutdown, etc.). The orchestrator runs initializers in order until one succeeds with a change set.
 * <p>
 * Return a {@link Future} and complete it when the result is ready. Any {@link Future}
 * implementation is acceptable.
 *
 * @see FDv2SourceResult
 * @see Synchronizer
 */
public interface Initializer extends Closeable {

    /**
     * Run the initializer to completion.
     * <p>
     * Intended to be called at most once per instance. Complete the returned future with
     * the result (change set or status), or complete it exceptionally on error.
     *
     * @return a Future that completes with the result
     */
    Future<FDv2SourceResult> run();

    /**
     * Whether this initializer must run before the startup timeout begins ticking.
     * <p>
     * Eager initializers run synchronously on the calling thread during
     * {@code FDv2DataSource.start()}, before work is dispatched to the executor.
     * This guarantees their data is available even with a timeout of zero,
     * matching the FDv1 behavior where cached data was loaded in the constructor.
     *
     * @return {@code true} if this initializer must complete before the timeout starts
     */
    default boolean isRequiredBeforeStartup() {
        return false;
    }
}

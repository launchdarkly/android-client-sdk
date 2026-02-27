package com.launchdarkly.sdk.android.subsystems;

import java.io.Closeable;
import java.util.concurrent.Future;

/**
 * Interface for an FDv2 data source synchronizer that produces a stream of results.
 * <p>
 * The orchestrator calls {@link #next()} repeatedly. Each call returns a {@link Future}
 * that completes with the next result (change set or status). After SHUTDOWN or TERMINAL_ERROR,
 * no further calls to next() should be made. INTERRUPTED and GOODBYE allow the orchestrator to
 * switch synchronizers or retry.
 * <p>
 * Only one outstanding next() call is expected at a time.
 * <p>
 * When {@link Closeable#close()} is called, the implementation must complete any outstanding
 * {@link #next()} future so that the orchestrator is not blocked indefinitely.
 *
 * @see FDv2SourceResult
 * @see Initializer
 */
public interface Synchronizer extends Closeable {

    /**
     * Request the next result from the stream.
     * <p>
     * Complete the returned future when the next result is available. The caller may call
     * next() again for the next result. Only one outstanding next() call is expected at a time.
     *
     * @return a Future that completes with the next result (change set or status)
     */
    Future<FDv2SourceResult> next();
}

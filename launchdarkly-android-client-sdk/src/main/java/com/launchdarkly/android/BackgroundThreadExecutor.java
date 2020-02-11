package com.launchdarkly.android;

import android.support.annotation.NonNull;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Executes all threads with priority android.os.Process.THREAD_PRIORITY_BACKGROUND.
 */
class BackgroundThreadExecutor {

    private final ThreadFactory threadFactory;

    BackgroundThreadExecutor() {
        this.threadFactory = new PriorityThreadFactory(android.os.Process.THREAD_PRIORITY_BACKGROUND);
    }

    @SuppressWarnings("SameParameterValue")
    ExecutorService newFixedThreadPool(int nThreads) {
        return new ThreadPoolExecutor(nThreads, nThreads, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<Runnable>(), threadFactory);
    }

    private static class PriorityThreadFactory implements ThreadFactory {

        private final int threadPriority;

        PriorityThreadFactory(int threadPriority) {
            this.threadPriority = threadPriority;
        }

        @Override
        public Thread newThread(@NonNull final Runnable runnable) {
            Runnable wrapperRunnable = new Runnable() {
                @Override
                public void run() {
                    try {
                        android.os.Process.setThreadPriority(threadPriority);
                    } catch (Throwable ignored) {
                    }
                    runnable.run();
                }
            };
            return new Thread(wrapperRunnable);
        }

    }

}

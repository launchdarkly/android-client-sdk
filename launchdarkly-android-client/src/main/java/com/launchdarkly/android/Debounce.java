package com.launchdarkly.android;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;

import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

/**
 * Created by jkodumal on 9/18/17.
 */
public class Debounce {

    private volatile ListenableFuture<Void> inFlight;
    private volatile Callable<Void> pending;
    private ListeningExecutorService service = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor());

    public synchronized void call(Callable<Void> task) {
        pending = task;

        schedulePending();
    }

    private synchronized void schedulePending() {
        if (pending == null) {
            return;
        }

        if (inFlight == null) {
            inFlight = service.submit(pending);
            pending = null;
            inFlight.addListener(new Runnable() {
                @Override
                public void run() {
                    inFlight = null;
                    schedulePending();
                }
            }, MoreExecutors.directExecutor());
        }
    }

}
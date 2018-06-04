package com.launchdarkly.android;


import com.google.common.util.concurrent.*;

import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

/**
 * Created by jkodumal on 9/18/17.
 */
public class Debounce {

    private volatile ListenableFuture<Void> inFlight;
    private volatile Callable<Void> pending;
    ListeningExecutorService service = MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor());


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
            Futures.addCallback(inFlight, new FutureCallback<Void>() {

                public void onSuccess(Void aVoid) {
                    inFlight = null;
                    schedulePending();
                }

                public void onFailure(Throwable throwable) {
                    inFlight = null;
                    schedulePending();
                }
            }, MoreExecutors.directExecutor());

        }
    }

}
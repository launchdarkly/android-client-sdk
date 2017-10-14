package com.launchdarkly.example;

import com.launchdarkly.android.Debounce;

import org.junit.Test;

import java.util.concurrent.Callable;

import static junit.framework.Assert.assertEquals;

/**
 * Created by pwray on 2017-09-27.
 */

public class DebounceTest {

    @Test
    public void callPendingNullReturnNoAction() {
        Debounce test = new Debounce();
        int expected = 0;
        int actual = 0;

        test.call(null);

        assertEquals("no change", expected, actual);
    }

    @Test
    public void callPendingSetReturnOne() throws InterruptedException {
        Debounce test = new Debounce();
        Integer expected = 1;
        final Integer[] actual = {0};

        test.call(new Callable<Void>() {
            @Override
            public Void call() throws Exception {
                actual[0] = 1;
                return null;
            }
        });
        Thread.sleep(3000);

        assertEquals("no change", expected, actual[0]);
    }

    @Test
    public void callPendingSetReturnTwo() throws InterruptedException {
        Debounce test = new Debounce();
        Integer expected = 2;
        final Integer[] actual = {0};

        test.call(new Callable<Void>() {
            @Override
            public Void call() throws Exception {
                actual[0] = 1;
                return null;
            }
        });
        Thread.sleep(3000);
        test.call(new Callable<Void>() {
            @Override
            public Void call() throws Exception {
                actual[0] = 2;
                return null;
            }
        });
        Thread.sleep(3000);

        assertEquals("no change", expected, actual[0]);
    }

    @Test
    public void callPendingSetReturnOneBounce() throws InterruptedException {
        Debounce test = new Debounce();
        Integer expected = 1;
        final Integer[] actual = {0};

        test.call(new Callable<Void>() {
            @Override
            public Void call() throws Exception {
                actual[0] = 1;
                return null;
            }
        });
        test.call(new Callable<Void>() {
            @Override
            public Void call() throws Exception {
                actual[0] = 2;
                return null;
            }
        });

        assertEquals("no change", expected, actual[0]);
    }
}

package com.launchdarkly.android;

import android.support.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.Callable;

import static org.junit.Assert.assertEquals;

@SuppressWarnings("deprecation")
@RunWith(AndroidJUnit4.class)
public class DebounceTest {

    @Test
    public void callPendingNullReturnNoAction() {
        Debounce test = new Debounce();
        int expected = 0;
        int actual = 0;

        test.call(null);

        assertEquals(expected, actual);
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

        assertEquals(expected, actual[0]);
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

        assertEquals(expected, actual[0]);
    }

    @Test
    public void callPendingSetReturnThreeBounce() throws InterruptedException {
        Debounce test = new Debounce();
        Integer expected = 3;
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
        test.call(new Callable<Void>() {
            @Override
            public Void call() throws Exception {
                if (actual[0] == 1)
                    actual[0] = 3;
                return null;
            }
        });
        Thread.sleep(1);

        assertEquals(expected, actual[0]);
    }
}

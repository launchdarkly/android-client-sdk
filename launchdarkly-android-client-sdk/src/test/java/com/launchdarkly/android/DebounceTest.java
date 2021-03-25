package com.launchdarkly.android;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

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

        test.call(() -> {
            actual[0] = 1;
            return null;
        });
        
        Thread.sleep(1000);

        assertEquals(expected, actual[0]);
    }

    @Test
    public void callPendingSetReturnTwo() throws InterruptedException {
        Debounce test = new Debounce();
        Integer expected = 2;
        final Integer[] actual = {0};

        test.call(() -> {
            actual[0] = 1;
            return null;
        });
        Thread.sleep(1000);
        test.call(() -> {
            actual[0] = 2;
            return null;
        });
        Thread.sleep(1000);

        assertEquals(expected, actual[0]);
    }

    @Test
    public void callPendingSetReturnThreeBounce() throws InterruptedException {
        Debounce test = new Debounce();
        Integer expected = 3;
        final Integer[] actual = {0};

        test.call(() -> {
            actual[0] = 1;
            Thread.sleep(100);
            return null;
        });
        test.call(() -> {
            actual[0] = 2;
            return null;
        });
        test.call(() -> {
            if (actual[0] == 1)
                actual[0] = 3;
            return null;
        });
        Thread.sleep(500);

        assertEquals(expected, actual[0]);
    }
}

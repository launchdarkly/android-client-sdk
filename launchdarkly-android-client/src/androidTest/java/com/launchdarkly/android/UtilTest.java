package com.launchdarkly.android;

import android.support.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertTrue;

/**
 * Created by pwray on 2017-09-19.
 */

@RunWith(AndroidJUnit4.class)
public class UtilTest {

    private int testValue;

    @Before
    public void setup() {
        testValue = 0;
    }

    @Test
    public void TestScheduleOneFunction() {
        // setup
        int expected = 1;
        Runnable addOne = new Runnable() {
            @Override
            public void run() {
                addOne();
            }
        };
        // execute
        boolean result = Util.queue(addOne);
        try {
            Thread.sleep(LDConfig.DEFAULT_CONNECTION_TIMEOUT_MILLIS + 2000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        // validate
        assertTrue("function scheduled", result);
        assertEquals("function worked", expected, testValue);
    }

    @Test
    public void TestScheduleThreeFunction() {
        // setup
        int expected = 3;
        Runnable addOne = new Runnable() {
            @Override
            public void run() {
                addOne();
            }
        };
        Runnable addTwo = new Runnable() {
            @Override
            public void run() {
                addTwo();
            }
        };
        Runnable addThree = new Runnable() {
            @Override
            public void run() {
                addThree();
            }
        };
        // execute
        boolean resultOne = Util.queue(addOne);
        boolean resultTwo = Util.queue(addTwo);
        boolean resultThree = Util.queue(addThree);
        try {
            Thread.sleep(LDConfig.DEFAULT_CONNECTION_TIMEOUT_MILLIS + 2000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        // validate
        assertTrue("function one scheduled", resultOne);
        assertTrue("function two scheduled", resultTwo);
        assertTrue("function three scheduled", resultThree);
        assertEquals("function worked", expected, testValue);
    }

    private void addOne() {
        testValue = 1;
    }

    private void addTwo() {
        testValue = 2;
    }

    private void addThree() {
        testValue = 3;
    }
}

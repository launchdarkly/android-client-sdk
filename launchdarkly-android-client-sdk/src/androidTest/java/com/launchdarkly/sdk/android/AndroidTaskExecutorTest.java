package com.launchdarkly.sdk.android;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.launchdarkly.logging.LDLogLevel;
import com.launchdarkly.logging.LogCapture;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@RunWith(AndroidJUnit4.class)
public class AndroidTaskExecutorTest {
    private final Application application = ApplicationProvider.getApplicationContext();

    @Rule
    public final LogCaptureRule logging = new LogCaptureRule();

    private final TaskExecutor taskExecutor;

    public AndroidTaskExecutorTest() {
        taskExecutor = new AndroidTaskExecutor(application, logging.logger);
    }

    @After
    public void shutdown() throws Exception {
        taskExecutor.close();
    }

    @Test
    public void executeOnMainThread() throws InterruptedException {
        // First, verify that this test is not already running on the main thread
        assertNotSame(Looper.getMainLooper(), Looper.myLooper());

        BlockingQueue<Looper> calls = new LinkedBlockingQueue<>();
        taskExecutor.executeOnMainThread(() -> {
            calls.add(Looper.myLooper());
        });
        Looper calledFrom = calls.poll(5, TimeUnit.SECONDS);
        assertNotNull("timed out waiting for task to execute", calledFrom);
        assertSame(Looper.getMainLooper(), calledFrom);
    }

    @Test
    public void executeOnMainThreadWhenAlreadyOnMainThread() throws InterruptedException {
        BlockingQueue<Looper> calls = new LinkedBlockingQueue<>();
        new Handler(Looper.getMainLooper()).post(() -> {
            calls.add(Looper.myLooper());

            AtomicBoolean called = new AtomicBoolean(false);
            taskExecutor.executeOnMainThread(() -> {
                called.set(true);
            });
            assertTrue("task should have executed synchronously, but did not",
                    called.get());
        });

        Looper calledFrom = calls.poll(5, TimeUnit.SECONDS);
        assertNotNull("timed out waiting for task to execute", calledFrom);
        assertSame(Looper.getMainLooper(), calledFrom);
    }

    @Test
    public void executeOnMainThreadCatchesAndLogsExceptions() throws InterruptedException {
        taskExecutor.executeOnMainThread(() -> {
            throw new RuntimeException("fake error");
        });

        LogCapture.Message message1 = logging.logCapture.requireMessage(LDLogLevel.ERROR, 5000);
        assertThat(message1.getText(), containsString("fake error"));
        LogCapture.Message message2 = logging.logCapture.requireMessage(LDLogLevel.DEBUG, 5000);
        assertThat(message2.getText(), containsString(this.getClass().getName())); // stacktrace
    }

    @Test
    public void scheduleTask() throws InterruptedException {
        CountDownLatch canExecute = new CountDownLatch(1), didExecute = new CountDownLatch(1);

        taskExecutor.scheduleTask(() -> {
            try {
                canExecute.await();
            } catch (InterruptedException e) {}
            didExecute.countDown();
        }, 0);

        canExecute.countDown();
        didExecute.await();
    }

    @Test
    public void scheduleTaskCatchesAndLogsExceptions() throws InterruptedException {
        taskExecutor.scheduleTask(() -> {
            throw new RuntimeException("fake error");
        }, 0);

        LogCapture.Message message1 = logging.logCapture.requireMessage(LDLogLevel.ERROR, 5000);
        assertThat(message1.getText(), containsString("fake error"));
        LogCapture.Message message2 = logging.logCapture.requireMessage(LDLogLevel.DEBUG, 5000);
        assertThat(message2.getText(), containsString(this.getClass().getName())); // stacktrace
    }

}

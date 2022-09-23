package com.launchdarkly.sdk.android;

import static android.app.PendingIntent.FLAG_IMMUTABLE;

import android.app.AlarmManager;
import android.app.Application;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.launchdarkly.logging.LDLogger;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Standard implementation of {@link TaskExecutor} for use in the Android environment. Besides
 * enforcing correct thread usage, this class also ensures that any unchecked exceptions thrown by
 * asynchronous tasks are caught and logged.
 */
final class AndroidTaskExecutor implements TaskExecutor {
    private static final String TASK_NUMBER_KEY = "task.number";

    // The following fields are static because an Intent can't contain a direct reference to a
    // specific instance of a class. We use a generated "task number" to tell us which task the
    // Intent is for.
    private static final ConcurrentHashMap<Long, TaskInfo> tasksByNumber = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Object, TaskInfo> tasksById = new ConcurrentHashMap<>();
    private static final AtomicReference<Application> appReference = new AtomicReference<>();
    private static final Object tasksLock = new Object();

    private final Application application;
    private final Handler handler;
    private final LDLogger logger;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

    AndroidTaskExecutor(Application application, LDLogger logger) {
        this.application = application;
        this.handler = new Handler(Looper.getMainLooper());
        this.logger = logger;

        // We hold a *static* reference to the application in appReference for use in callback code
        // that doesn't have a reference to the AndroidTaskExecutor instance.
        appReference.set(application);
    }

    @Override
    public void executeOnMainThread(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            callActionWithErrorHandling(action);
        } else {
            handler.post(wrapActionWithErrorHandling(action));
        }
    }

    @Override
    public ScheduledFuture<?> scheduleTask(Runnable action, long delayMillis) {
        return executor.schedule(action, delayMillis, TimeUnit.MILLISECONDS);
    }

    @Override
    public void startRepeatingTask(Object identifier, Runnable task, long initialDelayMillis, long intervalMillis) {
        // The logic here is a bit roundabout because of our use of the AlarmManager API. A
        // repeating "alarm" is identified by an Intent, which can only contain serializable values
        // like a class name or an integer-- not a direct reference to an object on the application
        // heap. This is because an alarm can outlive the application(*). But, for the sake of
        // encapsulation, we don't want to hard-code things so that the alarm always causes us to
        // call a specific method like the polling method; we'd like for other SDK components to be
        // able to just specify an arbitrary Runnable.
        //
        // The solution is that when we start a task, we generate a unique-ish number, embed that
        // number in the Intent, and put the number and the Runnable into our maps. Then when an
        // alarm fires for our class, we can locate the appropriate Runnable based on that number.
        // If the number isn't in our map, we assume it's an obsolete alarm and kill it.
        //
        // (* We don't actually want the alarm to outlive the application; we cancel it at shutdown
        // time, so it would only persist if the application has crashed unexpectedly. That might
        // mean that using AlarmManager at all is overkill and we could just just use the
        // ScheduledExecutorService.)
        long taskNumber;
        synchronized (tasksLock) {
            taskNumber = System.currentTimeMillis();
            TaskInfo taskInfo = new TaskInfo(task, taskNumber, this);
            tasksById.put(identifier, taskInfo);
            tasksByNumber.put(taskNumber, taskInfo);
        }
        try {
            getAlarmManager(application).setInexactRepeating(
                    AlarmManager.ELAPSED_REALTIME,
                    SystemClock.elapsedRealtime() + initialDelayMillis,
                    intervalMillis,
                    getPendingIntent(application, taskNumber));
        } catch (Exception ex) {
            LDUtil.logExceptionAtWarnLevel(logger, ex,
                    "Exception occurred when creating polling alarm, likely due to the host application having too many existing alarms");
        }
    }

    @Override
    public void stopRepeatingTask(Object identifier) {
        TaskInfo taskInfo;
        synchronized (tasksLock) {
            taskInfo = tasksById.get(identifier);
            if (taskInfo == null) {
                return;
            }
            tasksById.remove(identifier);
            tasksByNumber.remove(taskInfo.taskNumber);
        }
        getAlarmManager(application).cancel(getPendingIntent(application, taskInfo.taskNumber));
    }

    @Override
    public void close() {
        executor.shutdownNow();
        synchronized (tasksLock) {
            tasksByNumber.clear();
            tasksById.clear();
            // this ensures that if any old alarms fire after close() is called, they're no-ops
        }
    }

    private Runnable wrapActionWithErrorHandling(Runnable action) {
        return new Runnable() {
            @Override
            public void run() {
                callActionWithErrorHandling(action);
            }
        };
    }

    private void callActionWithErrorHandling(Runnable action) {
        try {
            if (action != null) {
                action.run();
            }
        } catch (RuntimeException e) {
            LDUtil.logExceptionAtErrorLevel(logger, e, "Unexpected exception from asynchronous task");
        }
    }

    private static AlarmManager getAlarmManager(Application application) {
        return (AlarmManager) application.getSystemService(Context.ALARM_SERVICE);
    }

    private static PendingIntent getPendingIntent(Application application, long number) {
        Intent intent = new Intent(application, RepeatingTaskComponent.class);
        intent.putExtra(TASK_NUMBER_KEY, number);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return PendingIntent.getBroadcast(application, 0, intent, FLAG_IMMUTABLE);
        } else {
            return PendingIntent.getBroadcast(application, 0, intent, 0);
        }
    }

    private static final class TaskInfo {
        final Runnable task;
        final long taskNumber;
        final AndroidTaskExecutor owner;

        TaskInfo(Runnable task, long taskNumber, AndroidTaskExecutor owner) {
            this.task = task;
            this.taskNumber = taskNumber;
            this.owner = owner;
        }
    }

    private static final class RepeatingTaskComponent extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            long taskNumber = intent.getLongExtra(TASK_NUMBER_KEY, -1);
            TaskInfo taskInfo;
            synchronized (tasksLock) {
                taskInfo = tasksByNumber.get(taskNumber);
            }
            if (taskInfo == null) {
                // This must be a leftover alarm from an earlier lifetime of the app, so we should
                // just cancel it now.
                Application application = appReference.get();
                if (application != null) {
                    getAlarmManager(application).cancel(getPendingIntent(application, taskNumber));
                }
            } else {
                taskInfo.owner.callActionWithErrorHandling(taskInfo.task);
            }
        }
    }
}

# LaunchDarkly Android SDK test app

This app is an internal quality harness. For customer integration examples, use `example`.

Add the following to the repository's root `local.properties`:

```properties
launchdarkly.mobileKey=mob-...
launchdarkly.environment=production
```

Set `launchdarkly.environment=staging` to use LaunchDarkly's staging endpoints.

## Event-loss scenarios

Create a boolean flag named `kill-flag`, or enter another flag key in the app. Tap
**Eval+track+kill** to evaluate the flag, track a stand-in error event, request a flush, and
terminate the process five seconds later. This exercises the interval between recording and
delivery without Android lifecycle callbacks masking the result.

The two immediate controls compare exits that application code can and cannot observe:

- **Eval+Kill now** records the same pair and sends `SIGKILL` immediately. No handler or SDK code
  can run before the process ends.
- **Eval+Crash now** throws an uncaught exception immediately after recording. The installed crash
  handler calls `flushAndWait` with a two-second budget before delegating to Android's handler.

Tier 3 configures `EventPersistence.IMMEDIATE`. With that setting, events recorded by
**Eval+Kill now** should be recovered and delivered after the next launch even though no process
code ran on exit.

## Over-refresh scenario

Create a boolean flag named `trackevents-test` with event tracking enabled. Tap
**Over-Refresh Eval** to compare the average evaluation cost while filling the event capacity and
after it is full. The loop intentionally runs on the main thread and is an internal stress test,
not an application integration pattern.

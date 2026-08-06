# LaunchDarkly Android SDK example app

## Configuration

The app reads its settings from `local.properties` in the repository root, which is not checked in.
Create it if it does not exist and add:

```properties
launchdarkly.mobileKey=your-mobile-key
launchdarkly.environment=production
```

| Property | Default | Description |
| --- | --- | --- |
| `launchdarkly.mobileKey` | none | Mobile key for the environment to connect to. The app refuses to initialize without it. |
| `launchdarkly.environment` | `production` | `staging` points the streaming, polling, and events endpoints at the `ld-stg.launchdarkly.com` hosts. Any other value uses the SDK defaults. |

These become `BuildConfig` fields, so change them and rebuild for them to take effect. The mobile key
has to match the environment: a production key against staging fails to authorize.

## Verifying evaluation exposure deduplication

The app registers two `ExposureCountingHook` instances with different dedupe windows, and each counts
the evaluation series stages it observes. The totals appear below the evaluation result:

```
Environment: production
Evaluations requested: 7
fast (5000 ms): 3 (before 3 / after 3)
slow (60000 ms): 1 (before 1 / after 1)
```

Each hook declares its own window in its constructor, which is how a hook shipped by a plugin would
choose its policy:

```java
evaluationExposureDeduper(new EvaluationExposureDeduper(dedupeWindowMillis, DEDUPE_MAX_SIZE));
```

The windows are the `FAST_DEDUPE_WINDOW_MILLIS` and `SLOW_DEDUPE_WINDOW_MILLIS` constants in
`MainActivity`, which registers both hooks without saying anything more about deduplication:

```java
Components.hooks().addHook(fastHook).addHook(slowHook)
```

Deduplication is opt-in per hook: a hook registered without a deduper observes every evaluation.
Passing `EvaluationExposureDeduper.disabled()` states that explicitly, and passing your own subclass
of `EvaluationExposureDeduper` replaces the policy entirely.

Enter a flag key, then tap **Evaluate Flag** repeatedly. "Evaluations requested" climbs with every
tap while each hook's count stays put, because repeated evaluations resolving to the same result
within that hook's window are suppressed for the whole series. Keep tapping past five seconds and the
`fast` count moves again while `slow` stays where it is. Tapping **Identify** clears both hooks'
caches, so the next evaluation reaches both. Analytics events are not deduplicated: every evaluation
is still counted by LaunchDarkly.

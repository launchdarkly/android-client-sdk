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

The app registers a hook that counts evaluation series stages and displays the totals below the
evaluation result:

```
Environment: production
Evaluation Exposure dedupe window: 60000 ms
Evaluations requested: 4
Reported to hooks: 1 (before 1 / after 1)
```

The window is the `EVALUATION_EXPOSURE_DEDUPE_WINDOW_MILLIS` constant in `MainActivity`, which the
app gives to that one hook:

```java
Components.hooks().addHook(exposureHook.evaluationExposureDeduper(60_000, 2_000))
```

Set it to `0` to turn deduplication off for the hook. Each hook is deduplicated on its own, so a
second hook registered with `EvaluationExposureDeduper.disabled()` would keep seeing every
evaluation. A hook registered without a deduper falls back to
`LDConfig.Builder.evaluationExposureDedupeWindowMillis`.

Enter a flag key, then tap **Evaluate Flag** repeatedly. "Evaluations requested" climbs with every
tap while "Reported to hooks" stays put, because repeated evaluations resolving to the same result
within the window are suppressed for the whole series. Tapping **Identify** clears the dedupe cache,
so the next evaluation is reported again. Analytics events are not deduplicated: every evaluation is
still counted by LaunchDarkly.

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

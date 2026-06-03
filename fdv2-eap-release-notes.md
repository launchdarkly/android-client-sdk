# Android Client SDK 5.13.0 — Release notes proposal (FDv2 EAP)

## Data Saving Mode EAP

This release adds support for our second generation flag delivery protocol, also known as data-saving mode.

This SDK version uses the first generation flag delivery protocol unless you explicitly configure the new protocol.

Support for the new protocol is defined by a data system configuration. Setting a data system via `Components.dataSystem()` enables the new protocol.

The data system supports more flexible configuration for **initializers** (how the SDK gets an initial payload) and **synchronizers** (how it stays up to date).

> **This is an Early Access feature.** The `dataSystem` configuration surface is subject to change without notice and is not covered by the SDK's semantic-versioning guarantees until it graduates to GA. Existing applications that do not call `.dataSystem(...)` are unaffected.

## Configuration

Several predefined data system configurations are available depending on how you deploy the SDK. The Android Client SDK exposes the data system through the same `ConnectionMode` enum it already uses; the syntactic shape differs from the server SDKs because Android retains its existing foreground/background lifecycle semantics under FDv2. See [Automatic mode switching](#automatic-mode-switching) below.

### Default

This is the LaunchDarkly-recommended default. It uses a two-phase strategy for initialization and includes fallbacks for synchronization in each connection mode.

The SDK gets an initial payload with a single request to the polling endpoints, then keeps it up to date via streaming in the foreground. If that initial polling request fails, the SDK can still initialize via streaming once it enters synchronization. During synchronization the SDK can fall back to polling if streaming has problems. The SDK automatically switches to polling in the background and during network changes.

```java
LDConfig config = new LDConfig.Builder(AutoEnvAttributes.Enabled)
    .mobileKey("my-key")
    .dataSystem(Components.dataSystem())
    .build();
```

### Polling only or streaming only

You can configure the SDK to use only polling or only streaming in the foreground. This is not the recommended mode, and fewer fallbacks are available. To prevent the SDK from switching modes based on app lifecycle or network state, disable automatic mode switching:

Polling:

```java
LDConfig config = new LDConfig.Builder(AutoEnvAttributes.Enabled)
    .mobileKey("my-key")
    .dataSystem(
        Components.dataSystem()
            .automaticModeSwitching(AutomaticModeSwitchingConfig.disabled())
            .foregroundConnectionMode(ConnectionMode.POLLING))
    .build();
```

Streaming:

```java
LDConfig config = new LDConfig.Builder(AutoEnvAttributes.Enabled)
    .mobileKey("my-key")
    .dataSystem(
        Components.dataSystem()
            .automaticModeSwitching(AutomaticModeSwitchingConfig.disabled())
            .foregroundConnectionMode(ConnectionMode.STREAMING))
    .build();
```

In streaming-only mode, polling may still be used if we need to disable the second generation protocol. In that case the data system is told to fall back to our v1 protocol and uses polling to do so.

### Offline

The top-level `offline(true)` configuration applies to the data system.

```java
LDConfig config = new LDConfig.Builder(AutoEnvAttributes.Enabled)
    .mobileKey("my-key")
    .offline(true)
    .build();
```

If both `offline(true)` and a data system (for example `Components.dataSystem()`) are set, the data system's initializers and synchronizers are not used.

```java
LDConfig config = new LDConfig.Builder(AutoEnvAttributes.Enabled)
    .mobileKey("my-key")
    .offline(true)
    .dataSystem(Components.dataSystem())
    .build();
```

### Custom configuration

For advanced use cases, you can customize the initializers and synchronizers used by a specific `ConnectionMode`. This is the Android equivalent of the `custom()` data system configuration in the server SDKs: per-mode initializers and synchronizers built from `DataSystemComponents`.

For example, polling once every six hours when the app is in the background:

```java
LDConfig config = new LDConfig.Builder(AutoEnvAttributes.Enabled)
    .mobileKey("my-key")
    .dataSystem(
        Components.dataSystem()
            .customizeConnectionMode(ConnectionMode.BACKGROUND,
                DataSystemComponents.customMode()
                    .initializers(DataSystemComponents.pollingInitializer())
                    .synchronizers(
                        DataSystemComponents.pollingSynchronizer()
                            .pollIntervalMillis(21_600_000))))
    .build();
```

### Automatic mode switching

Under FDv2 the `ConnectionMode` enum carries the same meaning it has today — the SDK still tracks foreground/background lifecycle, still respects network connectivity changes, and still exposes the same status surface. What changes is the data pipeline that backs each mode: per-mode initializers and synchronizers drawn from `DataSystemComponents` rather than the FDv1 polling/streaming components from `Components.pollingDataSource()` / `Components.streamingDataSource()`.

To disable automatic switching entirely, keeping the SDK in a single mode regardless of lifecycle or network changes:

```java
LDConfig config = new LDConfig.Builder(AutoEnvAttributes.Enabled)
    .mobileKey("my-key")
    .dataSystem(
        Components.dataSystem()
            .automaticModeSwitching(AutomaticModeSwitchingConfig.disabled())
            .foregroundConnectionMode(ConnectionMode.STREAMING))
    .build();
```

To disable lifecycle switching but keep network-driven switching:

```java
LDConfig config = new LDConfig.Builder(AutoEnvAttributes.Enabled)
    .mobileKey("my-key")
    .dataSystem(
        Components.dataSystem()
            .automaticModeSwitching(
                DataSystemComponents.automaticModeSwitching()
                    .lifecycle(false)
                    .network(true)
                    .build()))
    .build();
```

### Mutually exclusive with `dataSource(...)`

`LDConfig.Builder.dataSystem(...)` and `LDConfig.Builder.dataSource(...)` are mutually exclusive. The data system uses FDv2; `dataSource()` uses the legacy FDv1 protocol. If you call both, the last call wins.

---

## Notes for the author of the release commit

- Use a `feat(experimental): Release EAP support for FDv2 data system.` conventional-commit on the merge so release-please bumps the minor and the changelog wording matches the cross-SDK convention.
- This is a plain Maven Central release — no `-beta` classifier, no GitHub prerelease flag. Per [SDK-2440](https://launchdarkly.atlassian.net/browse/SDK-2440).
- okhttp-eventsource is pinned at `4.2.0` (added FDv2-required response-header access on 2026-01-22); no dependency bump needed for the EAP.
- Final version number depends on what release-please has already cut by EAP day. Update the `5.13.0` placeholder above before merging.

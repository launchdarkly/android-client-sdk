# SDK-1956: Research Plan — ConnectivityManager + FDv2 Platform State

**Ticket:** [SDK-1956](https://launchdarkly.atlassian.net/browse/SDK-1956) — Update ConnectivityManager to support platform state driven synchronizer configurations.

**Porting Plan Part:** 6 — Environment state in FDv2DataSource

**Goal:** Understand how `ConnectivityManager` works today, how it interacts with `LDClient` and the data source subsystem, and how to integrate it with FDv2's platform-state-driven mode switching — all without breaking the existing FDv1 behavior.

---

## Phase 1: How the Client Interacts with ConnectivityManager

### The creation chain

Everything starts in [`LDClient.init()`](../launchdarkly-android-client-sdk/src/main/java/com/launchdarkly/sdk/android/LDClient.java), which creates each `LDClient` instance. The constructor (line 314) creates the `ConnectivityManager`:

```java
connectivityManager = new ConnectivityManager(
        clientContextImpl,
        config.dataSource,       // ComponentConfigurer<DataSource> — the factory
        eventProcessor,
        contextDataManager,
        environmentStore
);
```

The key parameter is `config.dataSource` — a `ComponentConfigurer<DataSource>` (factory). If the user didn't call `config.dataSource(...)`, the default is `Components.streamingDataSource()` (set in [`LDConfig.Builder.build()`](../launchdarkly-android-client-sdk/src/main/java/com/launchdarkly/sdk/android/LDConfig.java) at line 519).

### LDClient's calls into ConnectivityManager

There are exactly **8 call sites** from `LDClient` to `ConnectivityManager`. These are the "upstream" touchpoints:

| LDClient method | ConnectivityManager call | Purpose |
|---|---|---|
| `init()` (static) | `startUp(callback)` | Begin data source on SDK initialization |
| `identifyInternal()` | `switchToContext(context, callback)` | Context change (new user) |
| `closeInternal()` | `shutDown()` | Stop everything |
| `setOfflineInternal()` | `setForceOffline(true)` | Go offline |
| `setOnlineStatusInternal()` | `setForceOffline(false)` | Go online |
| `isInitialized()` | `isForcedOffline()` + `isInitialized()` | Check initialization state |
| `getConnectionInformation()` | `getConnectionInformation()` | Public connection info |
| `register/unregisterStatusListener` | `register/unregisterStatusListener(...)` | Connection status callbacks |

**Key takeaway:** `LDClient` treats `ConnectivityManager` as a black box. It doesn't know about streaming vs. polling or FDv1 vs. FDv2. Changes to `ConnectivityManager`'s internals won't affect `LDClient` at all.

### Configuration that reaches ConnectivityManager

Two `LDConfig` settings flow into `ConnectivityManager`:

1. **`config.isOffline()`** → `forcedOffline` (user explicitly offline)
2. **`config.isDisableBackgroundPolling()`** → `backgroundUpdatingDisabled` (kill data source in background)

The data source builder itself (streaming vs. polling config, poll intervals, etc.) is opaque to `ConnectivityManager` — it only sees the `ComponentConfigurer<DataSource>` factory.

### Files to read

- [`LDClient.java`](../launchdarkly-android-client-sdk/src/main/java/com/launchdarkly/sdk/android/LDClient.java) — lines 100–270 (init flow), 314–321 (constructor), 375–386 (identify)
- [`LDConfig.java`](../launchdarkly-android-client-sdk/src/main/java/com/launchdarkly/sdk/android/LDConfig.java) — lines 65, 72, 79, 229, 519 (data source and background config)

---

## Phase 2: ConnectivityManager Internals

### The core decision engine: `updateDataSource()`

This is the single most important method to understand. All state transitions flow through it (lines 184–260 of [`ConnectivityManager.java`](../launchdarkly-android-client-sdk/src/main/java/com/launchdarkly/sdk/android/ConnectivityManager.java)). Here's the decision tree:

```
updateDataSource(mustReinitializeDataSource, onCompletion)
│
├─ Read current state:
│   forcedOffline?  platformState.isNetworkAvailable()?  platformState.isForeground()?
│
├─ IF forcedOffline → STOP data source, set SET_OFFLINE, DON'T start new
├─ ELIF no network → STOP data source, set OFFLINE, DON'T start new
├─ ELIF background + backgroundUpdatingDisabled → STOP, set BACKGROUND_DISABLED, DON'T start
├─ ELSE →
│     shouldStopExistingDataSource = mustReinitializeDataSource
│     shouldStartDataSourceIfStopped = true
│
├─ IF shouldStopExistingDataSource → stop current, clear reference
└─ IF shouldStartDataSourceIfStopped AND no current data source →
      build new DataSource via factory, start it
```

### Three triggers call `updateDataSource()`

1. **Network change** (line 147): `updateDataSource(false, ...)` — never forces a rebuild; just stops/starts based on connectivity.

2. **Foreground/background change** (line 152): First asks the current data source `needsRefresh(!foreground, currentContext)`. Only calls `updateDataSource(true, ...)` if the data source says it needs refreshing.

3. **Context switch** via `switchToContext()` (line 170): Same `needsRefresh` check, then `updateDataSource(true, ...)` if needed.

### How the StreamingDataSourceBuilder chooses between modes

This is a critical detail — the *builder* makes the streaming-vs-polling decision, not `ConnectivityManager`. See [`ComponentsImpl.java`](../launchdarkly-android-client-sdk/src/main/java/com/launchdarkly/sdk/android/ComponentsImpl.java) lines 326–348:

```java
// StreamingDataSourceBuilderImpl.build():
if (clientContext.isInBackground() && !streamEvenInBackground) {
    // In background: delegate to polling builder
    return Components.pollingDataSource()
            .backgroundPollIntervalMillis(backgroundPollIntervalMillis)
            .pollIntervalMillis(backgroundPollIntervalMillis)
            .build(clientContext);
}
// In foreground: create StreamingDataSource
return new StreamingDataSource(...);
```

So the FDv1 flow for foreground→background is:
1. `AndroidPlatformState` fires `onForegroundChanged(false)`
2. ConnectivityManager's listener calls `StreamingDataSource.needsRefresh(true, ctx)` → returns `true` (unless `streamEvenInBackground`)
3. `updateDataSource(true, ...)` stops the streaming data source
4. Calls `StreamingDataSourceBuilderImpl.build()` with `inBackground=true` → creates a **PollingDataSource** instead
5. Starts the new PollingDataSource

**The entire streaming↔polling switch for FDv1 happens through tear-down and rebuild.** This is exactly what FDv2 wants to avoid.

### The `needsRefresh` contract

The [`DataSource.needsRefresh()`](../launchdarkly-android-client-sdk/src/main/java/com/launchdarkly/sdk/android/subsystems/DataSource.java) default returns `true` (always rebuild). [`StreamingDataSource`](../launchdarkly-android-client-sdk/src/main/java/com/launchdarkly/sdk/android/StreamingDataSource.java) overrides it (lines 258–262):

```java
public boolean needsRefresh(boolean newInBackground, LDContext newEvaluationContext) {
    return !newEvaluationContext.equals(context) ||
            (newInBackground && !streamEvenInBackground);
}
```

The current `FDv2DataSource` on Todd's branch (`origin/ta/SDK-1817/composite-src-pt2`) does **not** override `needsRefresh`, so it inherits the default `return true` — meaning ConnectivityManager tears it down on every state change. **This is the gap your ticket fills.**

### Files to read

- [`ConnectivityManager.java`](../launchdarkly-android-client-sdk/src/main/java/com/launchdarkly/sdk/android/ConnectivityManager.java) — entire file (~420 lines), focus on lines 123–160 (constructor + listeners), 184–260 (`updateDataSource`), 170–183 (`switchToContext`)
- [`ComponentsImpl.java`](../launchdarkly-android-client-sdk/src/main/java/com/launchdarkly/sdk/android/ComponentsImpl.java) — lines 252–299 (polling builder), 326–348 (streaming builder)
- [`StreamingDataSource.java`](../launchdarkly-android-client-sdk/src/main/java/com/launchdarkly/sdk/android/StreamingDataSource.java) — lines 258–262 (`needsRefresh`)
- [`PlatformState.java`](../launchdarkly-android-client-sdk/src/main/java/com/launchdarkly/sdk/android/PlatformState.java) — entire file (~30 lines, interface only)
- [`AndroidPlatformState.java`](../launchdarkly-android-client-sdk/src/main/java/com/launchdarkly/sdk/android/AndroidPlatformState.java) — skim for understanding of foreground/network detection

---

## Phase 3: Integration Options for FDv2

### The fundamental difference

In FDv1, `ConnectivityManager` handles mode switching by **destroying and recreating** the data source. The builder decides what type to create based on `clientContext.isInBackground()`.

In FDv2 (per the CSFDV2 spec and porting plan Part 6), `FDv2DataSource` should handle mode switching **internally** — swapping between synchronizer configurations (e.g., streaming→background polling) without being torn down. ConnectivityManager should only rebuild on **context changes**.

### What needs to change (at a high level)

1. **`FDv2DataSource.needsRefresh()`** — Override to return `false` for foreground/background-only changes and `true` for context changes. This prevents ConnectivityManager from tearing it down on lifecycle transitions.

2. **`FDv2DataSource` must subscribe to `PlatformState`** — It needs to receive foreground/background and network events directly, so it can internally switch its synchronizer configuration (e.g., foreground mode uses streaming sync, background mode uses polling @ 1hr).

3. **ConnectivityManager doesn't need to change much** — The `needsRefresh` contract already supports this. If `FDv2DataSource.needsRefresh()` returns `false` for background changes, ConnectivityManager will keep it alive. The existing `updateDataSource` logic handles the "no network → stop" and "forced offline → stop" cases generically, which is correct for FDv2 too.

4. **Named connection modes** (from the CSFDV2 spec) — The mode table (`streaming`, `polling`, `offline`, `one-shot`, `background`) maps each mode name to a set of initializers and synchronizers. The `FDv2DataSource` needs a way to receive "switch to mode X" signals and reconfigure itself accordingly.

### What you can work on while Todd works on the network layer

Your ticket is about the *upstream* side — making `ConnectivityManager` and the data source lifecycle work with platform-state-driven mode switching. Concretely:

- **Passing `PlatformState` to `FDv2DataSource`** so it can self-manage mode transitions
- **The `needsRefresh()` override** on `FDv2DataSource`
- **The debouncing mechanism** (CSFDV2 spec says 1-second debounce window for network/lifecycle/mode events)
- **How `ConnectivityManager` creates `FDv2DataSource`** — what configuration it passes, and ensuring it doesn't interfere with FDv2's self-management
- **Ensuring FDv1 behavior is unchanged** — all the existing `StreamingDataSource`/`PollingDataSource` paths must work exactly as before

### Key constraint: no breaking changes

The good news is that all the code you'd be modifying is internal:

- `ConnectivityManager` is package-private
- `FDv2DataSource` is package-private (not yet wired into production)
- `DataSource.needsRefresh()` is already a `default` method — adding overrides doesn't break anything
- `PlatformState` is an internal interface
- `ClientContextImpl` is internal

The public API surface (`LDClient`, `LDConfig`, `Components`, `ConnectionInformation`) doesn't need to change for Part 6.

### Reference code to study

When ready to go deeper, these are the closest analogues in other SDKs:

- **js-core** [`CompositeDataSource.ts`](../../js-core/packages/shared/common/src/datasource/CompositeDataSource.ts) — the orchestrator that handles mode switching
- **js-core PR #1135** — [Ryan's mode table types and configuration schema](https://github.com/launchdarkly/js-core/pull/1135)
- **java-core** [`FDv2DataSource.java`](../../java-core/lib/sdk/server/src/main/java/com/launchdarkly/sdk/server/FDv2DataSource.java) — server-side orchestrator (simpler, no foreground/background)

---

## Suggested reading order

1. [`PlatformState.java`](../launchdarkly-android-client-sdk/src/main/java/com/launchdarkly/sdk/android/PlatformState.java) — tiny interface, sets the stage
2. [`DataSource.java`](../launchdarkly-android-client-sdk/src/main/java/com/launchdarkly/sdk/android/subsystems/DataSource.java) — the interface, especially `needsRefresh` Javadoc
3. [`ConnectivityManager.java`](../launchdarkly-android-client-sdk/src/main/java/com/launchdarkly/sdk/android/ConnectivityManager.java) — the heart of the matter, read top to bottom
4. [`StreamingDataSource.java`](../launchdarkly-android-client-sdk/src/main/java/com/launchdarkly/sdk/android/StreamingDataSource.java) lines 258–262 and [`ComponentsImpl.java`](../launchdarkly-android-client-sdk/src/main/java/com/launchdarkly/sdk/android/ComponentsImpl.java) lines 326–348 — how FDv1 mode switching works today
5. `FDv2DataSource.java` on Todd's branch (`git show origin/ta/SDK-1817/composite-src-pt2:launchdarkly-android-client-sdk/src/main/java/com/launchdarkly/sdk/android/FDv2DataSource.java`) — the orchestrator that needs platform state awareness
6. [`.cursor/rules/fdv2-client-side-spec.mdc`](../.cursor/rules/fdv2-client-side-spec.mdc) — the target behavior from the CSFDV2 spec

---

## Key classes at a glance

| Class | Visibility | Role | Changes needed? |
|---|---|---|---|
| `LDClient` | **public** | Entry point, delegates to ConnectivityManager | No |
| `LDConfig` | **public** | User config, holds `ComponentConfigurer<DataSource>` | No (for Part 6) |
| `Components` | **public** | Factory methods (`streamingDataSource()`, etc.) | No (for Part 6) |
| `ConnectivityManager` | package-private | Data source lifecycle, platform state reactions | Minor — ensure FDv2 path works with `needsRefresh=false` |
| `PlatformState` | package-private | Interface for foreground/network state | No |
| `AndroidPlatformState` | package-private | Android implementation of PlatformState | No |
| `DataSource` | **public interface** | `start()`, `stop()`, `needsRefresh()` | No |
| `StreamingDataSource` | package-private | FDv1 streaming | No |
| `PollingDataSource` | package-private | FDv1 polling | No |
| `FDv2DataSource` | package-private | FDv2 orchestrator (Todd's branch) | Yes — `needsRefresh()` override, PlatformState subscription, mode switching |
| `ClientContextImpl` | package-private | Internal context with PlatformState | Possibly — may need to pass PlatformState to FDv2DataSource |
| `ConnectionInformation` | **public** | Connection status reporting | No |

---

## ConnectivityManager field reference

For quick reference, these are the fields in `ConnectivityManager` (lines 50–74):

| Field | Type | Purpose |
|---|---|---|
| `baseClientContext` | `ClientContext` | Base client context |
| `platformState` | `PlatformState` | Foreground/background and network state |
| `dataSourceFactory` | `ComponentConfigurer<DataSource>` | Builds `DataSource` instances |
| `dataSourceUpdateSink` | `DataSourceUpdateSink` | Sink for flag updates and status |
| `connectionInformation` | `ConnectionInformationState` | Connection mode and last success/failure |
| `environmentStore` | `PerEnvironmentData` | Per-environment store |
| `eventProcessor` | `EventProcessor` | Event processor |
| `foregroundListener` | `ForegroundChangeListener` | Foreground/background listener |
| `connectivityChangeListener` | `ConnectivityChangeListener` | Network listener |
| `taskExecutor` | `TaskExecutor` | Task scheduling |
| `backgroundUpdatingDisabled` | `boolean` | From `config.isDisableBackgroundPolling()` |
| `statusListeners` | `List<WeakReference<LDStatusListener>>` | Status listeners |
| `forcedOffline` | `AtomicBoolean` | User-set offline |
| `started` | `AtomicBoolean` | Whether `startUp()` has run |
| `closed` | `AtomicBoolean` | Whether `shutDown()` has run |
| `currentDataSource` | `AtomicReference<DataSource>` | Active data source |
| `currentContext` | `AtomicReference<LDContext>` | Current evaluation context |
| `previouslyInBackground` | `AtomicReference<Boolean>` | Previous foreground/background state |
| `initialized` | `volatile boolean` | Whether initial data load completed |

---

## FDv2DataSource field reference (Todd's branch)

| Field | Type | Purpose |
|---|---|---|
| `evaluationContext` | `LDContext` | Context for evaluations |
| `dataSourceUpdateSink` | `DataSourceUpdateSinkV2` | Applies change sets and status |
| `sourceManager` | `SourceManager` | Manages initializer/synchronizer list |
| `fallbackTimeoutSeconds` | `long` | Default 120s — INTERRUPTED → try next sync |
| `recoveryTimeoutSeconds` | `long` | Default 300s — non-prime → retry prime |
| `sharedExecutor` | `ScheduledExecutorService` | For condition timers |
| `started`, `startCompleted`, `stopped` | `AtomicBoolean` | Lifecycle state |

**Notable absence:** No `PlatformState`, no foreground/background awareness, no mode table. These are the things your ticket adds.

---

## Type location reference (updated March 2026)

FDv2 shared types have been moved from local Android SDK code to `launchdarkly-java-sdk-internal:1.9.0` in the `com.launchdarkly.sdk.fdv2` package:

| Type | Package | Notes |
|---|---|---|
| `ChangeSet<T>` | `com.launchdarkly.sdk.fdv2` | Now generic; Android uses `ChangeSet<Map<String, DataModel.Flag>>` |
| `ChangeSetType` | `com.launchdarkly.sdk.fdv2` | Enum: `Full`, `Partial`, `None` |
| `Selector` | `com.launchdarkly.sdk.fdv2` | Was `com.launchdarkly.sdk.internal.fdv2.sources.Selector` |
| `SourceResultType` | `com.launchdarkly.sdk.fdv2` | Enum: `CHANGE_SET`, `STATUS` |
| `SourceSignal` | `com.launchdarkly.sdk.fdv2` | Enum: `INTERRUPTED`, `TERMINAL_ERROR`, `SHUTDOWN`, `GOODBYE` |
| `FDv2SourceResult` | `com.launchdarkly.sdk.android.subsystems` | Remains local; wraps the shared enums |
| `DataSourceState` | `com.launchdarkly.sdk.android.subsystems` | Local enum: `INITIALIZING`, `VALID`, `INTERRUPTED`, `OFF` |

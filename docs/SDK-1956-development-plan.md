# SDK-1956 Development Plan — FDv2 Connection Mode Configuration

## Key Insights from js-core

Based on Ryan Lamb's recent PRs in [js-core](https://github.com/launchdarkly/js-core), the FDv2 client-side architecture separates into four distinct layers. Each layer is built and tested independently, and the Android implementation should follow the same decomposition.

### Layer 1: Mode Types and Mode Table (PR [#1135](https://github.com/launchdarkly/js-core/pull/1135), merged)

Pure configuration types with no behavior:

- **`FDv2ConnectionMode`** — named mode: `streaming`, `polling`, `offline`, `one-shot`, `background`
- **`DataSourceEntry`** — JS discriminated union (not used in Android; replaced by `ComponentConfigurer`)
- **`ModeDefinition`** — `{ initializers: ComponentConfigurer<Initializer>[], synchronizers: ComponentConfigurer<Synchronizer>[] }`
- **`MODE_TABLE`** — built-in map of every `FDv2ConnectionMode` → `ModeDefinition`
- **`LDClientDataSystemOptions`** — user-facing config: `initialConnectionMode`, `backgroundConnectionMode`, `automaticModeSwitching`
- **`PlatformDataSystemDefaults`** — per-platform defaults (Android: foreground=streaming, background=background, automaticModeSwitching=true)

### Layer 2: Mode Resolution (PR [#1146](https://github.com/launchdarkly/js-core/pull/1146), open)

A pure function + data-driven table that maps platform state → connection mode:

- **`ModeState`** — input: `{ lifecycle, networkAvailable, foregroundMode, backgroundMode }`
- **`ModeResolutionEntry`** — `{ conditions: Partial<ModeState>, mode: FDv2ConnectionMode | ConfiguredMode }`
- **`ModeResolutionTable`** — ordered list of entries; first match wins
- **`resolveConnectionMode(table, input)`** — evaluates the table, returns a `FDv2ConnectionMode`
- **`MOBILE_TRANSITION_TABLE`** — the Android default:
  1. `{ networkAvailable: false }` → `'offline'`
  2. `{ lifecycle: 'background' }` → configured background mode
  3. `{ lifecycle: 'foreground' }` → configured foreground mode

The js-core resolver supports **`ConfiguredMode`** indirection: `{ configured: 'foreground' }` resolves to `input.foregroundMode`, `{ configured: 'background' }` resolves to `input.backgroundMode`. **In this PR, we simplify by hardcoding the Android defaults** (foreground=STREAMING, background=BACKGROUND) directly in the resolution table. User-configurable foreground/background mode selection is deferred to a future PR.

### Layer 3: State Debouncing (PR [#1148](https://github.com/launchdarkly/js-core/pull/1148), open)

A separate `StateDebounceManager` component that coalesces rapid platform events:

- Tracks three independent dimensions: `networkState`, `lifecycleState`, `requestedMode`
- Each change resets a 1-second timer (CONNMODE spec 3.5.4)
- When the timer fires, `onReconcile(pendingState)` is called with the final accumulated state
- `identify()` does NOT participate in debouncing (spec 3.5.6) — it bypasses the debouncer
- `close()` cancels pending timers; further calls become no-ops

### Layer 4: FDv2DataSource Orchestrator (PR [#1141](https://github.com/launchdarkly/js-core/pull/1141), merged)

The orchestrator in js-core (`createFDv2DataSource`) is structurally similar to Todd's Android `FDv2DataSource`:

- Takes `initializerFactories` and `synchronizerSlots` (with Available/Blocked state)
- Runs initializers sequentially, then enters the synchronizer loop
- Uses `Conditions` (fallback/recovery timers) to decide when to switch synchronizers
- Receives a `selectorGetter` from the outside — it does NOT manage the selector internally
- Has `start()` and `close()` — currently no `switchMode()` method

Key observation: The JS orchestrator does **not** have a `switchMode()` method yet. Mode switching will likely be handled by the consumer layer that creates and manages the orchestrator. For Android, we need to decide whether `FDv2DataSource` gets a `switchMode()` method or whether mode switching is handled externally.

### Supporting PRs (js-core)

- **Cache Initializer** (PR [#1147](https://github.com/launchdarkly/js-core/pull/1147), draft): Reads cached flags from storage, returns as a `changeSet` without a selector. Orchestrator sees `dataReceived=true` and continues to next initializer.
- **Polling Initializer/Synchronizer** (PR [#1130](https://github.com/launchdarkly/js-core/pull/1130), merged): FDv2 polling using `FDv2ProtocolHandler`, handles 304 Not Modified, recoverable vs terminal errors.
- **Streaming Initializer/Synchronizer** (PR [#1131](https://github.com/launchdarkly/js-core/pull/1131), merged): FDv2 streaming via EventSource, supports one-shot (initializer) and long-lived (synchronizer) modes, ping handling, fallback detection.

### Layer 5: Android Concrete Initializer/Synchronizer Implementations (PR [#325](https://github.com/launchdarkly/android-client-sdk/pull/325), open)

Todd's PR adds the concrete Android implementations of `Initializer` and `Synchronizer`. These are the components that our `ComponentConfigurer` factory methods will create:

- **`FDv2PollingInitializer`** — Single-shot poll. Implements `Initializer`. Dependencies: `FDv2Requestor`, `SelectorSource`, `Executor`, `LDLogger`. Returns `CHANGE_SET` on success, `TERMINAL_ERROR` on failure.
- **`FDv2PollingSynchronizer`** — Recurring poll on `ScheduledExecutorService`. Implements `Synchronizer`. Dependencies: `FDv2Requestor`, `SelectorSource`, `ScheduledExecutorService`, `initialDelayMillis`, `pollIntervalMillis`, `LDLogger`. Results delivered via `LDAsyncQueue`.
- **`FDv2StreamingSynchronizer`** — Long-lived SSE connection via `EventSource`. Implements `Synchronizer`. Dependencies: `HttpProperties`, `streamBaseUri`, `requestPath`, `LDContext`, `useReport`, `evaluationReasons`, `SelectorSource`, optional `FDv2Requestor` (for `ping` events), `initialReconnectDelayMillis`, `DiagnosticStore`, `LDLogger`.
- **`FDv2PollingBase`** — Abstract base for polling. Shared `doPoll()` logic: drives `FDv2ProtocolHandler`, translates changesets via `FDv2ChangeSetTranslator`, maps errors to `TERMINAL_ERROR` (oneShot) vs `INTERRUPTED` (recurring).
- **`FDv2Requestor` / `DefaultFDv2Requestor`** — Interface + OkHttp implementation for FDv2 polling HTTP requests. Supports GET/REPORT, `basis` query param (selector), ETag tracking for 304 Not Modified, payload filters.
- **`FDv2ChangeSetTranslator`** — Converts `FDv2ChangeSet` → `ChangeSet<Map<String, Flag>>`. Filters for `flag_eval` kind only.
- **`SelectorSource` / `SelectorSourceFacade`** — Interface + adapter for reading the current `Selector` from `TransactionalDataStore` without coupling to the update sink.
- **`LDAsyncQueue<T>`** — Thread-safe async queue: producers `put()`, consumers `take()` as futures. Used by synchronizers.
- **`LDFutures.anyOf`** — Now generic (`<T>`) and returns `LDAwaitFuture<T>`. Used to race result queues against shutdown futures.

**FDv2DataSource changes in PR #325:**
- Now uses `sharedExecutor.execute()` instead of `new Thread()` for the orchestrator loop.
- Added javadoc to constructors.
- Minor: removed unused `Selector` import from `DataSourceUpdateSinkV2`.

---

## Scope — This PR

**In scope:**
- `ConnectionMode` enum with 5 built-in modes (closed enum, no custom modes)
- `ModeDefinition` + `DEFAULT_MODE_TABLE` with `ComponentConfigurer` entries
- `ModeState` with platform state only (`foreground`, `networkAvailable`)
- `ModeResolutionTable` with hardcoded Android defaults (foreground→STREAMING, background→BACKGROUND, no network→OFFLINE)
- `switchMode(ConnectionMode)` on `FDv2DataSource`
- ConnectivityManager integration (mode resolution in foreground/network listeners)
- `FDv2DataSourceBuilder` (resolves `ComponentConfigurer` → `DataSourceFactory`)

**Deferred to future PRs:**
- Custom named connection modes (spec 5.3.5 TBD)
- User-configurable foreground/background mode selection (CONNMODE 2.2.2) — adds `foregroundMode`/`backgroundMode` to `ModeState` and config options to `LDConfig`
- Mode table partial overrides (user overriding initializer/synchronizer lists for a built-in mode)
- State debouncing (`StateDebounceManager`)
- `automaticModeSwitching` config option (granular lifecycle/network toggle)
- Mode switch optimization (spec 5.3.8 TBD — retain active data source if equivalent config). See note below.

---

## FDv2DataSource Is Not Aware of Platform State

**FDv2DataSource should not subscribe to `PlatformState` or know about foreground/background.** It only knows about named connection modes and their corresponding initializer/synchronizer pipelines. The mapping from platform state → connection mode happens externally.

---

## Architecture

```
┌──────────────────────┐
│   PlatformState      │  fires foreground/background + network events
└──────────┬───────────┘
           │
           ▼
┌──────────────────────────────────────┐
│  StateDebounceManager (future PR)    │
│                                      │
│  • Accumulates network + lifecycle   │
│    changes                           │
│  • 1-second debounce window          │
│  • Fires onReconcile(pendingState)   │
│    after quiet period                │
│  • identify() bypasses debouncing    │
└──────────┬───────────────────────────┘
           │ onReconcile(pendingState)
           ▼
┌──────────────────────────────────────┐
│  Mode Resolution                     │
│                                      │
│  • Build ModeState(foreground,       │
│    networkAvailable) from platform   │
│  • ModeResolutionTable.MOBILE        │
│    .resolve(modeState)               │
│  • Hardcoded: fg→STREAMING,          │
│    bg→BACKGROUND, no net→OFFLINE     │
└──────────┬───────────────────────────┘
           │ resolved ConnectionMode
           ▼
┌──────────────────────────────────────┐
│  ConnectivityManager                 │
│                                      │
│  • Owns the current DataSource       │
│  • If ModeAware: calls     │
│    switchMode(resolvedMode)          │
│  • If FDv1 DataSource: existing      │
│    teardown/rebuild behavior         │
│  • identify() → needsRefresh() →    │
│    full teardown/rebuild             │
└──────────┬───────────────────────────┘
           │ switchMode(BACKGROUND)
           ▼
┌──────────────────────────────────────┐
│   FDv2DataSource                     │
│                                      │
│  • Holds the mode table              │
│    (ConnectionMode → ModeDefinition) │
│  • On switchMode(): stop current     │
│    synchronizers, start new ones     │
│    from the target mode's definition │
│  • Does NOT re-run initializers      │
│    on mode switch (spec 2.0.1)       │
│  • needsRefresh() returns false for  │
│    background changes, true for      │
│    context changes                   │
└──────────────────────────────────────┘
```

### Key Separation of Concerns

| Concern | Owner |
|---------|-------|
| Detecting platform state (foreground, network) | `PlatformState` / `AndroidPlatformState` |
| Coalescing rapid state changes | `StateDebounceManager` (future PR) |
| Mapping platform state → connection mode | `ModeResolutionTable.MOBILE.resolve()` (hardcoded defaults) |
| Data source lifecycle (start, stop, rebuild on identify) | `ConnectivityManager` |
| Commanding mode switches | `ConnectivityManager` via `ModeAware.switchMode()` |
| Orchestrating initializer/synchronizer pipelines | `FDv2DataSource` |
| Mode definitions (what each mode uses) | `MODE_TABLE` (`Map<ConnectionMode, ModeDefinition>`) |

---

## New Types to Create

All types are **package-private** in `com.launchdarkly.sdk.android` (internal to the SDK, no public API changes).

### 1. `ConnectionMode` (enum)

```java
enum ConnectionMode {
    STREAMING, POLLING, OFFLINE, ONE_SHOT, BACKGROUND
}
```

Maps to JS `FDv2ConnectionMode`. Closed enum — custom modes are out of scope for this PR (spec 5.3.5 is TBD and unresolved).

### 2. `ModeDefinition`

```java
final class ModeDefinition {
    final List<ComponentConfigurer<Initializer>> initializers;
    final List<ComponentConfigurer<Synchronizer>> synchronizers;
}
```

Uses the SDK's existing `ComponentConfigurer<T>` pattern (which takes `ClientContext` at build time) rather than a custom `DataSourceEntry` config type. This eliminates the need for a separate config-to-factory conversion step — the mode table directly holds factory functions.

Helper factory methods provide readable construction:

```java
static ComponentConfigurer<Initializer> pollingInitializer() { ... }
static ComponentConfigurer<Synchronizer> pollingSynchronizer(long intervalMs) { ... }
static ComponentConfigurer<Synchronizer> streamingSynchronizer() { ... }
static ComponentConfigurer<Initializer> cacheInitializer() { ... }  // stubbed for now
```

### 3. Default Mode Table

```java
static final Map<ConnectionMode, ModeDefinition> DEFAULT_MODE_TABLE = ...;
```

Contents match the JS `MODE_TABLE`:

| Mode | Initializers | Synchronizers |
|------|-------------|---------------|
| STREAMING | cache, polling | streaming, polling |
| POLLING | cache | polling |
| OFFLINE | cache | (none) |
| ONE_SHOT | cache, polling, streaming | (none) |
| BACKGROUND | cache | polling @ 3600s |

At build time, `FDv2DataSourceBuilder.build(clientContext)` resolves each `ComponentConfigurer` into a `DataSourceFactory` (Todd's zero-arg factory pattern) by partially applying the `ClientContext`:

```java
DataSourceFactory<Initializer> factory = () -> configurer.build(clientContext);
```

This resolution produces a `Map<ConnectionMode, ResolvedModeDefinition>` where `ResolvedModeDefinition` holds `List<DataSourceFactory<Initializer>>` and `List<DataSourceFactory<Synchronizer>>`. FDv2DataSource only works with the resolved factories — it never sees `ComponentConfigurer` or `ClientContext`.

### 4. `ModeState`

```java
final class ModeState {
    final boolean foreground;
    final boolean networkAvailable;
}
```

Represents the current platform state. All fields required, primitive `boolean` types. Built by ConnectivityManager from `PlatformState` events.

In this PR, `ModeState` only carries platform state. User-configurable foreground/background mode selection (CONNMODE 2.2.2) is deferred to a future PR. When that's added, `foregroundMode` and `backgroundMode` fields will be introduced here and the resolution table entries will reference them via lambdas instead of hardcoded enum values.

### 5. `ModeResolutionEntry`

```java
final class ModeResolutionEntry {
    final Predicate<ModeState> conditions;   // does this entry apply to the given state?
    final ConnectionMode mode;               // the resolved mode if this entry matches
}
```

With hardcoded defaults, the resolver is a simple `ConnectionMode` value rather than a `Function<ModeState, ConnectionMode>`. When user-configurable mode selection is added later, `mode` can be replaced with a `Function<ModeState, ConnectionMode>` resolver to support indirection like `state -> state.foregroundMode`.

### 6. `ModeResolutionTable` + `resolve()` (pure function)

```java
final class ModeResolutionTable {
    static final ModeResolutionTable MOBILE = new ModeResolutionTable(Arrays.asList(
        new ModeResolutionEntry(
            state -> !state.networkAvailable,
            ConnectionMode.OFFLINE),
        new ModeResolutionEntry(
            state -> !state.foreground,
            ConnectionMode.BACKGROUND),
        new ModeResolutionEntry(
            state -> state.foreground,
            ConnectionMode.STREAMING)
    ));

    ConnectionMode resolve(ModeState state) { ... }
}
```

`resolve()` iterates entries in order. The first entry whose `conditions` predicate returns `true` wins, and its `mode` value is returned. Pure function — no side effects, no platform awareness.

**Adaptation note:** This is a Java-idiomatic adaptation of Ryan Lamb's mode resolution code from js-core PR [#1146](https://github.com/launchdarkly/js-core/pull/1146). The js-core version uses `Partial<ModeState>` for conditions (partial object matching) and `ConfiguredMode` indirection for user-configurable modes. In this PR, we simplify: conditions become `Predicate<ModeState>` and modes are hardcoded `ConnectionMode` enum values. The data-driven table structure is preserved so that user-configurable mode selection can be added later by replacing the `ConnectionMode mode` field with a `Function<ModeState, ConnectionMode>` resolver.

### 7. `ModeAware` (package-private interface)

```java
interface ModeAware extends DataSource {
    void switchMode(ConnectionMode newMode);
}
```

FDv2DataSource implements this. ConnectivityManager checks `instanceof ModeAware` to decide whether to use mode resolution or legacy FDv1 behavior.

---

## Changes to Existing Code

### `FDv2DataSource`

1. **Implement `ModeAware`** (which extends `DataSource`).
   - `ModeAware` is a marker interface with a single method: `void switchMode(ConnectionMode newMode)`. All logic lives in `FDv2DataSource`.
   - Alternative: skip the interface entirely and have ConnectivityManager use `instanceof FDv2DataSource` directly. The interface is a thin abstraction; either approach works.
2. **Add `switchMode(ConnectionMode)` method:**
   - Look up the new mode in the mode table to get its `ModeDefinition`.
   - Stop current synchronizers (close the active `SourceManager`).
   - Create a new `SourceManager` with the new mode's synchronizer factories.
   - Signal the background thread to resume the synchronizer loop with new factories.
   - Do NOT re-run initializers (spec 2.0.1).
3. **Override `needsRefresh()`:**
   - Return `false` when only the background state changed (mode-aware data source handles this via `switchMode`).
   - Return `true` when the evaluation context changed (requires full teardown/rebuild).
4. **New constructor** that accepts:
   - The resolved mode table (`Map<ConnectionMode, ResolvedModeDefinition>`) — already contains `DataSourceFactory` instances
   - The starting `ConnectionMode`
   - Keep existing constructors for backward compatibility with Todd's tests.

### `ConnectivityManager`

1. **Foreground listener:** After `needsRefresh()` returns false, if the data source is a `ModeAware`, build a `ModeState` from current platform state, resolve the mode via `ModeResolutionTable.MOBILE`, and call `switchMode()` if the mode changed.
2. **Network listener:** Same pattern — resolve mode and call `switchMode()`.
3. **Configuration:** ConnectivityManager needs access to the `ModeResolutionTable` (see open question #1). With hardcoded defaults, no user-configurable mode selection is needed in this PR.

### No Changes

- `DataSource` interface (public API)
- `StreamingDataSource`, `PollingDataSource` (FDv1 paths)
- `PlatformState`, `AndroidPlatformState`
- `ClientContext`, `ClientContextImpl`
- `LDConfig` public API

---

## Implementation Order (Small, Incremental Commits)

The work is decomposed into small commits that each build on the previous one. Each commit should compile and not break existing tests.

### Commit 1: All new types (new files only, no changes to existing code)

| File | Description |
|------|-------------|
| `ConnectionMode.java` | Enum: STREAMING, POLLING, OFFLINE, ONE_SHOT, BACKGROUND |
| `ModeDefinition.java` | `List<ComponentConfigurer<Initializer>>` + `List<ComponentConfigurer<Synchronizer>>` + DEFAULT_MODE_TABLE + helper factory methods |
| `ModeState.java` | Platform state for mode resolution: `boolean foreground`, `boolean networkAvailable` |
| `ModeResolutionEntry.java` | Predicate (`Predicate<ModeState>`) + hardcoded `ConnectionMode` |
| `ModeResolutionTable.java` | Ordered list + `resolve()` method + MOBILE constant |
| `ModeAware.java` | Package-private interface extending DataSource with `switchMode(ConnectionMode)` |

Tests: `ModeResolutionTable.resolve()` with various `ModeState` inputs.

### Commit 2: `ModeAware` implementation on `FDv2DataSource`

| File | Description |
|------|-------------|
| `FDv2DataSource.java` (modify) | Implement `ModeAware`, override `needsRefresh()`, add stub `switchMode()` |

Tests: `needsRefresh()` returns false for background-only changes, true for context changes.

### Commit 3: `switchMode()` implementation

| File | Description |
|------|-------------|
| `FDv2DataSource.java` (modify) | Full `switchMode()` — stop synchronizers, swap to new mode's factories, resume |

### Commit 4: `FDv2DataSourceBuilder`

| File | Description |
|------|-------------|
| `FDv2DataSourceBuilder.java` (new) | `ComponentConfigurer<DataSource>` that builds mode-aware FDv2DataSource |

The builder resolves `ComponentConfigurer<Initializer/Synchronizer>` → `DataSourceFactory<Initializer/Synchronizer>` by partially applying the `ClientContext`. This bridges the SDK's `ComponentConfigurer` pattern (used in the mode table) with Todd's `DataSourceFactory` pattern (used inside `FDv2DataSource`).

### Commit 5: ConnectivityManager mode resolution integration

| File | Description |
|------|-------------|
| `ConnectivityManager.java` (modify) | Add mode resolution for `ModeAware` instances in foreground/network listeners |

### Future PR: State debouncing

| File | Description |
|------|-------------|
| `StateDebounceManager.java` (new) | Android port of js-core's `StateDebounceManager` — sits between PlatformState and mode resolution |

### Future PR: Mode switch optimization (spec 5.3.8)

Spec 5.3.8 says the SDK SHOULD retain active data sources when switching modes if the old and new modes have equivalent synchronizer configuration. This avoids unnecessary teardown/rebuild when, for example, a user configures both streaming and background modes to use the same synchronizers.

**Approach: instance equality (`==`) on factories.** The simplest way to determine if two synchronizers are "equivalent" is to check if they are the *same instance*. This works if the `DEFAULT_MODE_TABLE` (and any future user overrides) shares `ComponentConfigurer` instances across modes where the configuration is identical. At build time, `FDv2DataSourceBuilder` resolves each `ComponentConfigurer` into a `DataSourceFactory`. If two modes reference the same `ComponentConfigurer` instance, they get the same `DataSourceFactory` instance, and `==` comparison identifies them as equivalent.

**Implication for mode table construction:** factory helper methods (e.g., `pollingInitializer()`, `streamingSynchronizer()`) should return shared static instances for the default configurations. Different configurations (e.g., polling at 30s vs. 3600s) produce different instances, so `==` correctly identifies them as non-equivalent.

**Implication for `SourceManager`:** Todd is interested in enhancing `SourceManager` to support this optimization. Instead of closing all synchronizers and building new ones on `switchMode()`, `SourceManager` could diff the old and new synchronizer factory lists using `==`, keep running any that are shared, and only tear down removed / start added synchronizers. This change is internal to `SourceManager` and `FDv2DataSource` — it doesn't affect the `ModeAware.switchMode(ConnectionMode)` contract or the mode resolution layer.

**Current PR:** This PR uses approach B (create a new `SourceManager` on each mode switch — full teardown/rebuild). The instance-equality optimization can be added later without changing any external interfaces.

---

## Branch Dependencies

Our work builds on two of Todd's branches:

| Branch | PR | Status | What we depend on |
|--------|-----|--------|-------------------|
| `ta/SDK-1817/composite-src-pt2` | (base) | In progress | `FDv2DataSource`, `SourceManager`, `FDv2DataSourceConditions`, `Initializer`/`Synchronizer` interfaces, `DataSourceFactory<T>`, `FDv2SourceResult`, `DataSourceUpdateSinkV2` |
| `ta/SDK-1835/initializers-synchronizers` | [#325](https://github.com/launchdarkly/android-client-sdk/pull/325) | Open | `FDv2PollingInitializer`, `FDv2PollingSynchronizer`, `FDv2StreamingSynchronizer`, `FDv2Requestor`/`DefaultFDv2Requestor`, `FDv2ChangeSetTranslator`, `SelectorSource`/`SelectorSourceFacade`, `LDAsyncQueue`, `LDFutures.anyOf<T>` |

**Our branching strategy:** Branch off `ta/SDK-1835/initializers-synchronizers` (which itself targets `ta/SDK-1817/composite-src-pt2`). Our commits (1–6) can be developed independently of PR #325 merging — we only need the types/interfaces, not the running implementations. However, Commit 5 (`FDv2DataSourceBuilder`) will reference the concrete constructors from PR #325 directly.

---

## Open Questions

### 1. How does ConnectivityManager get the mode resolution table?

With hardcoded defaults (no user-configurable foreground/background mode selection in this PR), the simplest approach is for ConnectivityManager to use `ModeResolutionTable.MOBILE` directly — it's a static constant. No `ModeResolutionConfig` object or builder interface is needed in this PR.

When user-configurable mode selection is added in a future PR, ConnectivityManager will need to receive the configured foreground/background modes. At that point, options include a `ModeResolutionProvider` marker interface on the builder, a constructor parameter on ConnectivityManager, or putting config into `ClientContextImpl`.

### 2. How does the mode table connect to concrete implementations?

**Answered.** The mode table holds `ComponentConfigurer<Initializer>` and `ComponentConfigurer<Synchronizer>` entries (the SDK's established factory pattern). At build time, `FDv2DataSourceBuilder.build(clientContext)` resolves each one into a `DataSourceFactory` (Todd's zero-arg factory pattern) by partially applying the `ClientContext`:

```java
DataSourceFactory<Initializer> factory = () -> configurer.build(clientContext);
```

The concrete types created by the factory methods (from Todd's PR #325):

| Factory method | Creates |
|---------------|---------|
| `pollingInitializer()` | `FDv2PollingInitializer(requestor, selectorSource, executor, logger)` |
| `pollingSynchronizer(intervalMs)` | `FDv2PollingSynchronizer(requestor, selectorSource, scheduledExecutor, initialDelayMs, intervalMs, logger)` |
| `streamingSynchronizer()` | `FDv2StreamingSynchronizer(httpProperties, streamBaseUri, requestPath, context, useReport, evaluationReasons, selectorSource, requestor, initialReconnectDelayMs, diagnosticStore, logger)` |
| `cacheInitializer()` | (stubbed for now; cache initializer not yet implemented on Android) |

All dependencies come from `ClientContext` at build time. `FDv2DataSource` only works with resolved `DataSourceFactory` instances — it never sees `ComponentConfigurer` or `ClientContext`.

### 3. Should we add debouncing now?

**Answer: No.** Debouncing will be added in a subsequent PR/commit. The architecture supports it — a `StateDebounceManager` (modeled after js-core's `StateDebounceManager`) sits between PlatformState listeners and mode resolution. The current implementation will call `switchMode()` directly from the listeners, and debouncing wraps this later.

### 4. What about `needsRefresh` and network changes?

ConnectivityManager's network listener currently calls `updateDataSource(false, ...)`, which can stop the data source if the network is unavailable. For FDv2 with mode resolution, network loss → resolve to OFFLINE mode → `switchMode(OFFLINE)`. This means the network listener needs the same `instanceof ModeAware` check as the foreground listener.

We need to ensure that when mode resolution is active, the existing `updateDataSource` logic for network changes is bypassed for `ModeAware` instances.

### 5. Should `switchMode()` be synchronous or asynchronous?

`switchMode()` is called from listener threads (foreground/network events). FDv2DataSource runs its synchronizer loop on a background thread. The call must signal the background thread to swap synchronizers.

Design: `switchMode()` sets the desired mode atomically and closes the current `SourceManager` (which interrupts the active synchronizer's `next()` future). The background thread detects the mode change, creates a new `SourceManager` with the new mode's factories, and re-enters the synchronizer loop. This is the same pattern as the existing `stop()` method.

---

## Reference Code

### js-core FDv2 Architecture

| Component | PR | File | Status |
|-----------|-----|------|--------|
| Mode types + table | [#1135](https://github.com/launchdarkly/js-core/pull/1135) | [`FDv2ConnectionMode.ts`][1], [`DataSourceEntry.ts`][2], [`ModeDefinition.ts`][3], [`ConnectionModeConfig.ts`][4], [`LDClientDataSystemOptions.ts`][5] | Merged |
| Mode resolution | [#1146](https://github.com/launchdarkly/js-core/pull/1146) | [`ModeResolution.ts`][6], [`ModeResolver.ts`][7] | Open |
| State debouncer | [#1148](https://github.com/launchdarkly/js-core/pull/1148) | [`StateDebounceManager.ts`][8] | Open |
| FDv2 orchestrator | [#1141](https://github.com/launchdarkly/js-core/pull/1141) | [`FDv2DataSource.ts`][9], [`SourceManager.ts`][10], [`Conditions.ts`][11] | Merged |
| Polling init/sync | [#1130](https://github.com/launchdarkly/js-core/pull/1130) | `PollingInitializer.ts`, `PollingSynchronizer.ts`, `PollingBase.ts` | Merged |
| Streaming init/sync | [#1131](https://github.com/launchdarkly/js-core/pull/1131) | `StreamingInitializerFDv2.ts`, `StreamingSynchronizerFDv2.ts`, `StreamingFDv2Base.ts` | Merged |
| Cache initializer | [#1147](https://github.com/launchdarkly/js-core/pull/1147) | [`CacheInitializer.ts`][12] | Draft |

[1]: /Users/azeisler/code/launchdarkly/js-core/packages/shared/sdk-client/src/api/datasource/FDv2ConnectionMode.ts
[2]: /Users/azeisler/code/launchdarkly/js-core/packages/shared/sdk-client/src/api/datasource/DataSourceEntry.ts
[3]: /Users/azeisler/code/launchdarkly/js-core/packages/shared/sdk-client/src/api/datasource/ModeDefinition.ts
[4]: /Users/azeisler/code/launchdarkly/js-core/packages/shared/sdk-client/src/datasource/ConnectionModeConfig.ts
[5]: /Users/azeisler/code/launchdarkly/js-core/packages/shared/sdk-client/src/api/datasource/LDClientDataSystemOptions.ts
[6]: /Users/azeisler/code/launchdarkly/js-core/packages/shared/sdk-client/src/api/datasource/ModeResolution.ts
[7]: /Users/azeisler/code/launchdarkly/js-core/packages/shared/sdk-client/src/datasource/ModeResolver.ts
[8]: /Users/azeisler/code/launchdarkly/js-core/packages/shared/sdk-client/src/datasource/StateDebounceManager.ts
[9]: /Users/azeisler/code/launchdarkly/js-core/packages/shared/sdk-client/src/datasource/fdv2/FDv2DataSource.ts
[10]: /Users/azeisler/code/launchdarkly/js-core/packages/shared/sdk-client/src/datasource/fdv2/SourceManager.ts
[11]: /Users/azeisler/code/launchdarkly/js-core/packages/shared/sdk-client/src/datasource/fdv2/Conditions.ts
[12]: /Users/azeisler/code/launchdarkly/js-core/packages/shared/sdk-client/src/datasource/fdv2/CacheInitializer.ts

### Android SDK — Orchestrator (Todd's branch `ta/SDK-1817/composite-src-pt2`)

- FDv2DataSource: [`FDv2DataSource.java`](/Users/azeisler/code/launchdarkly/android-client-sdk/launchdarkly-android-client-sdk/src/main/java/com/launchdarkly/sdk/android/FDv2DataSource.java)
- SourceManager: [`SourceManager.java`](/Users/azeisler/code/launchdarkly/android-client-sdk/launchdarkly-android-client-sdk/src/main/java/com/launchdarkly/sdk/android/SourceManager.java)
- DataSourceFactory: defined inside `FDv2DataSource.java` as `DataSourceFactory<T>` with `T build()`
- Initializer interface: [`Initializer.java`](/Users/azeisler/code/launchdarkly/android-client-sdk/launchdarkly-android-client-sdk/src/main/java/com/launchdarkly/sdk/android/subsystems/Initializer.java)
- Synchronizer interface: [`Synchronizer.java`](/Users/azeisler/code/launchdarkly/android-client-sdk/launchdarkly-android-client-sdk/src/main/java/com/launchdarkly/sdk/android/subsystems/Synchronizer.java)

### Android SDK — Concrete Initializers/Synchronizers (Todd's branch `ta/SDK-1835/initializers-synchronizers`, PR [#325](https://github.com/launchdarkly/android-client-sdk/pull/325))

- FDv2PollingInitializer: `FDv2PollingInitializer.java` — single-shot poll, implements `Initializer`
- FDv2PollingSynchronizer: `FDv2PollingSynchronizer.java` — recurring poll, implements `Synchronizer`
- FDv2StreamingSynchronizer: `FDv2StreamingSynchronizer.java` — SSE stream, implements `Synchronizer`
- FDv2PollingBase: `FDv2PollingBase.java` — shared polling logic (protocol handler + changeset translation)
- FDv2Requestor: `FDv2Requestor.java` — polling HTTP interface
- DefaultFDv2Requestor: `DefaultFDv2Requestor.java` — OkHttp implementation with ETag + selector support
- FDv2ChangeSetTranslator: `FDv2ChangeSetTranslator.java` — `FDv2ChangeSet` → `ChangeSet<Map<String, Flag>>`
- SelectorSource: `SelectorSource.java` — interface for current `Selector`
- SelectorSourceFacade: `SelectorSourceFacade.java` — adapts `TransactionalDataStore` to `SelectorSource`
- LDAsyncQueue: defined inside `LDFutures.java` — async producer/consumer queue

### Android SDK — Existing (main branch)

- ConnectivityManager: [`ConnectivityManager.java`](/Users/azeisler/code/launchdarkly/android-client-sdk/launchdarkly-android-client-sdk/src/main/java/com/launchdarkly/sdk/android/ConnectivityManager.java)
- DataSource interface: [`DataSource.java`](/Users/azeisler/code/launchdarkly/android-client-sdk/launchdarkly-android-client-sdk/src/main/java/com/launchdarkly/sdk/android/subsystems/DataSource.java)
- PlatformState: [`PlatformState.java`](/Users/azeisler/code/launchdarkly/android-client-sdk/launchdarkly-android-client-sdk/src/main/java/com/launchdarkly/sdk/android/PlatformState.java)
- LDClient construction: [`LDClient.java` line 423](/Users/azeisler/code/launchdarkly/android-client-sdk/launchdarkly-android-client-sdk/src/main/java/com/launchdarkly/sdk/android/LDClient.java)

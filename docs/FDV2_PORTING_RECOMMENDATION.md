# FDv2 Porting Recommendation: Java Server SDK → Android Client SDK

This document analyzes the FDv2 changes in the java-core patch (`fdv2-work.patch`) and recommends a **sequence of parts/chunks** to port FDv2 support into the Android Client SDK, with incremental integration and minimal risk.

---

## 1. Summary

### What the Java Server SDK patch does (31 commits)

| Area | Description |
|------|-------------|
| **Shared internal (java-core)** | FDv2 payload parsing and protocol: `fdv2.payloads.*`, `FDv2ProtocolHandler`, `FDv2ChangeSet`, `Selector`; `IterableAsyncQueue` in internal. |
| **Data system configuration** | `DataSystemBuilder`, `DataSystemConfiguration`, `DataSystemModes`, `FDv2PollingDataSourceBuilder`, `FDv2StreamingDataSourceBuilder` — initializers, synchronizers, FDv1 fallback, persistent store mode. |
| **Store & status** | `TransactionalDataStore`, `TransactionalDataSourceUpdateSink`, `DataSourceUpdateSinkV2` (apply + updateStatus/getDataStoreStatusProvider). |
| **FDv2 data source layer** | `FDv2DataSource` (orchestrates initializers/synchronizers), `FDv2Requestor`, `FDv2ChangeSetTranslator`, `PollingBase`, `PollingInitializerImpl`, `PollingSynchronizerImpl`, `StreamingSynchronizerImpl`, `SelectorSource`. |
| **Integration** | `FDv1DataSystem` vs `FDv2DataSystem`, LDConfig/LDClient wiring, fallback and recovery, headers, goodbye handling, file data source, TestDataV2, thread priority, offline handling. |

### How the Android plan maps to this

- **No full DataSystem.** We do not port `DataSystemBuilder` or `DataSystemModes`. We implement **FDv2DataSource** (Part 3) first, then the **streaming** (Part 4) and **polling** (Part 5) synchronizers/initializers that plug into it. **Part 6** wires environment state (foreground/background, network, offline) into FDv2DataSource so synchronizers can be enabled/disabled by state. **Part 7** integrates FDv2 with the existing **ConnectivityManager** (Option 1: single CM for both FDv1 and FDv2). The “mode” behavior is exposed as three **Components** methods (Part 8): **defaultDataSourceFDv2()**, **streamingDataSourceFDv2()**, **pollingDataSourceFDv2()**. Apps opt in via `dataSource(Components.defaultDataSourceFDv2())` (or the other two); there is **no useFDv2** config flag.

- **Sink: apply only.** We add **TransactionalDataSourceUpdateSink** and **DataSourceUpdateSinkV2** with **apply(ChangeSet)** only. We do **not** add updateStatus or getDataStoreStatusProvider; FDv2 data sources use the existing **setStatus(ConnectionMode, Throwable)** and **shutDown()**.

- **ContextDataManager and selector.** The existing sink implementation is extended to implement the new interfaces and delegate **apply(ChangeSet)** to **ContextDataManager**. ContextDataManager is updated to apply full, partial, and none changesets to in-memory state and persistence. The **Selector** from each changeset is stored **in memory only** (not persisted), consistent with the server SDK; it is exposed so FDv2 streaming and polling can send it (e.g. as `basis`).

- **Port order.** Part 1 (dependency + shared FDv2 protocol) → Part 2 (sink + apply + ContextDataManager + selector) → Part 3 (FDv2DataSource + Initializer/Synchronizer interfaces) → Part 4 (FDv2 streaming) → Part 5 (FDv2 polling) → Part 6 (environment state: pipe state into FDv2DataSource, enable/disable synchronizers by state, refactor for FDv1/FDv2 compatibility) → Part 7 (single ConnectivityManager for FDv1 and FDv2 — Option 1) → Part 8 (configuration: the three Components methods) → Part 9 (fallback, recovery, goodbye, diagnostics).

- **Out of scope for this plan.** Full DataSystem/Modes, persistent store mode, conditional persistence propagation, segments (we handle flags only), file data source, TestDataV2 (unless needed for tests), Redis or other server-only stores.

---

## 2. Recommended Porting Sequence (Chunks)

Port in the following order so each chunk is testable and keeps the SDK buildable. **Unit testing:** Each part includes adding or updating unit tests so that coverage grows incrementally with the code. At the end of every part, the new or changed behavior is covered by unit tests (and existing tests still pass). The bullets below call out the unit-test expectations for each part.

---

### **Part 1: Dependency and shared FDv2 protocol (foundation)**

**Goal:** Use FDv2 payload and protocol types from java-core internal; no Android-specific behavior yet.

**Actions:**

- Bump `launchdarkly-java-sdk-internal` to a version that includes:
  - `com.launchdarkly.sdk.internal.fdv2.payloads.*`
  - `com.launchdarkly.sdk.internal.fdv2.sources.FDv2ProtocolHandler`, `FDv2ChangeSet`, `Selector`
  - If `IterableAsyncQueue` is in internal, use it; otherwise add a small local async queue for use in FDv2 data sources.
- No new public API or config; no new data sources yet.
- **Unit tests:** Run the full existing test suite and build to confirm no regressions. No new production code is added, so no new unit tests are required; the deliverable is that all current tests still pass.

**Deliverable:** Build depends on internal FDv2 types; all current tests pass.

---

### **Part 2: TransactionalDataSourceUpdateSink and DataSourceUpdateSinkV2 (apply)**

**Goal:** Add the sink APIs required for FDv2 so data sources can apply changesets instead of only init/upsert.

**Actions:**

- Define **ChangeSet** (and any related types) for Android — e.g. a type that represents a full, partial, or none set of flag updates (key, version, full vs partial vs none). Include a **Selector** in the changeset (from `com.launchdarkly.sdk.internal.fdv2.sources.Selector`) and optional environment/context identifier. Align with server SDK’s `DataStoreTypes.ChangeSet` semantics where useful; use Android’s data model (`DataModel.Flag` or equivalent). Support **ChangeSetType.None** (no flag changes; may still carry a selector to store in memory).
- Add **TransactionalDataSourceUpdateSink** interface with a single method, e.g. `boolean apply(ChangeSet changeSet)` — apply the given changeset to the store; return true if successful. “Transactional” here means apply atomically where possible.
- Add **DataSourceUpdateSinkV2** interface extending **TransactionalDataSourceUpdateSink** only (no additional methods). FDv2 data sources will use the existing **DataSourceUpdateSink.setStatus(ConnectionMode, Throwable)** and **shutDown()** for all status reporting; do not add updateStatus or getDataStoreStatusProvider.
- Extend the existing component that implements **DataSourceUpdateSink** (**ConnectivityManager.DataSourceUpdateSinkImpl**) so it also implements **DataSourceUpdateSinkV2**. In **apply(ChangeSet)** delegate to ContextDataManager.
- Update **ContextDataManager** to support **apply(ChangeSet)**:
  - Add a method (e.g. `apply(ChangeSet)` or `applyChangeSet(...)`) that applies the changeset to in-memory flag state and to persistence, reusing the same context/version logic as `init` and `upsert`.
  - For **full** changesets: update in-memory state and persist in one logical step (use existing init path or equivalent; consider using **PersistentDataStore.setValues** where possible for atomic persistence).
  - For **partial** changesets: apply each change with existing version checks; persist updated state (ideally in a single atomic write for the context’s data + index where the store supports it).
  - For **none** changesets: do not change flag data; update the **Selector** from the changeset if present.
  - After any apply that carries a selector: **store the Selector in memory** so FDv2 data sources can send it on the next request (e.g. `basis` query param). The server SDK does not persist the selector (it is in-memory only in InMemoryDataStore); match that for now — do **not** persist the selector to PersistentDataStoreWrapper.
- **Selector storage (in-memory only):** When apply(changeSet) is called, update the current selector from the changeset. Hold it in memory (e.g. on ContextDataManager or the component that implements the sink). Expose the current selector (e.g. **SelectorSource**-style interface or getter on the sink/store) so FDv2 streaming and polling can read it when building requests. After process restart the selector is effectively empty, consistent with server behavior.
- Ensure **ClientContext** (or equivalent) can supply a **DataSourceUpdateSinkV2** when building FDv2 data sources — the same sink instance should implement both **DataSourceUpdateSink** and **DataSourceUpdateSinkV2** so existing code and FDv2 code can share it (no API change to ClientContext required if the concrete type implements both).
- **Test mocks:** Update **MockComponents.MockDataSourceUpdateSink** (and any other test sinks that implement DataSourceUpdateSink) to implement **DataSourceUpdateSinkV2** with stub **apply()** so existing tests continue to compile and FDv2 tests can use the mock.
- **Unit tests:** Add unit tests for: **apply(ChangeSet)** with full, partial, and none changesets (in-memory state and persistence behavior); in-memory selector storage and exposure after apply; sink implementing both DataSourceUpdateSink and DataSourceUpdateSinkV2. Ensure test mocks (e.g. MockDataSourceUpdateSink) are covered so FDv2 tests can rely on them.

**Deliverable:** TransactionalDataSourceUpdateSink and DataSourceUpdateSinkV2 (apply only; no updateStatus/getDataStoreStatusProvider) in the Android SDK; ContextDataManager (and sink implementation) support apply(ChangeSet) for full, partial, and none changesets; selector is stored in memory only and exposed for FDv2 (no persistence); ConnectivityManager.DataSourceUpdateSinkImpl implements both sink interfaces; FDv2 data sources use existing setStatus/shutDown for status; test mocks updated; unit tests for apply and selector; FDv2 data sources can call apply() and read the selector.

---

### **Part 3: FDv2DataSource (initializers + synchronizers together)**

**Goal:** Implement the FDv2DataSource orchestrator and Initializer/Synchronizer contracts **before** the concrete streaming and polling implementations. Part 4 and Part 5 will then provide the specific synchronizer/initializer implementations that plug into this.

**Actions:**

- Define **Initializer** and **Synchronizer** interfaces (or equivalent) that produce/stream FDv2 results (e.g. `FDv2SourceResult`-like). These are the contracts that Part 4 (streaming) and Part 5 (polling) will implement.
- Port **FDv2DataSource** (or equivalent name) that:
  - Accepts a list of initializer factories and synchronizer factories (and optionally FDv1 fallback).
  - Implements Android’s **DataSource** interface.
  - On start: runs initializers in order until one succeeds and provides initial data (with optional selector); then runs synchronizers, switching on failure/GOODBYE as in the server SDK.
  - Uses **DataSourceUpdateSinkV2.apply(ChangeSet)** for each CHANGESET from any initializer or synchronizer.
  - Manages lifecycle (start/stop, active source, shutdown).
- For Part 3, FDv2DataSource can be tested with mock or minimal stub implementations of Initializer/Synchronizer; the real streaming and polling implementations are added in Part 4 and Part 5.
- **Unit tests:** Add unit tests for FDv2DataSource: first initializer succeeds and provides data; initializers run in order until one succeeds; synchronizers run after initializer; fallback between synchronizers on failure/GOODBYE; shutdown cleans up and stops all. Use mock Initializer and Synchronizer implementations so Part 3 is fully covered before Part 4 and Part 5 add real implementations.

**Deliverable:** FDv2DataSource and Initializer/Synchronizer interfaces in the Android SDK; orchestrator runs initializers then synchronizers with fallback; unit tests with mocks/stubs; concrete streaming/polling implementations follow in Part 4 and Part 5.

---

### **Part 4: FDv2 streaming (SSE + FDv2 events)**

**Goal:** Implement the FDv2 streaming synchronizer (and optional initializer) that plugs into FDv2DataSource. Reuse the same EventSource/SSE pattern as current streaming; parse FDv2 events and call apply(ChangeSet).

**Actions:**

- Confirm **FDv2 streaming URL and semantics** for mobile (path, query params such as `filter`, context/selector if any).
- Implement the streaming **Synchronizer** (and optionally **Initializer**) that Part 3’s FDv2DataSource can use:
  - Reuse the same **EventSource**-based SSE connection pattern as `StreamingDataSource`, but parse incoming events as FDv2 and feed them into `FDv2ProtocolHandler`.
  - On CHANGESET: convert `FDv2ChangeSet` to Android `ChangeSet` and call `DataSourceUpdateSinkV2.apply(changeSet)`.
  - On GOODBYE: follow server guidance (e.g. reconnect with new selector or signal fallback).
  - Reuse HTTP/auth/headers from `LDUtil.makeHttpProperties(clientContext)` and existing endpoint selection.
- Add **FDv2StreamingDataSourceBuilder** (or equivalent) with options (e.g. initial reconnect delay, service endpoints) for use when building FDv2DataSource configurations.
- **Unit tests:** Add unit tests for: FDv2 event parsing and protocol handling (CHANGESET → apply, GOODBYE handling); synchronizer/initializer calling DataSourceUpdateSinkV2.apply() with correct ChangeSet; builder options. Optionally add tests with mock SSE or in-memory event stream to verify end-to-end flow within the unit test scope.

**Deliverable:** FDv2 streaming synchronizer/initializer and builder; unit tests for protocol and apply; can be used as a building block for FDv2DataSource in Part 3; existing FDv1 streaming unchanged.

---

### **Part 5: FDv2 polling (requestor + polling data source)**

**Goal:** Implement the FDv2 polling initializer and synchronizer that plug into FDv2DataSource. Part 3’s FDv2DataSource will use these for defaultMode() and polling().

**Actions:**

- Define the **FDv2 polling endpoint** for mobile (may be a new path under existing base, e.g. under `StandardEndpoints`; confirm with backend).
- Introduce an **FDv2 requestor** abstraction (e.g. `FDv2Requestor` interface): e.g. `poll(Selector)` → result containing list of `FDv2Event` and response headers. Implement **DefaultFDv2Requestor** (or equivalent) using OkHttp.
- Implement the polling **Initializer** and **Synchronizer** that Part 3’s FDv2DataSource can use:
  - Use `FDv2ProtocolHandler` to process events into CHANGESET, ERROR, GOODBYE; for CHANGESET call `DataSourceUpdateSinkV2.apply(changeSet)`.
  - Handle GOODBYE and errors (set status; fallback is handled by FDv2DataSource).
- Add **FDv2PollingDataSourceBuilder** (or equivalent) with options (e.g. poll interval, service endpoints) for use when building FDv2DataSource configurations.
- **Unit tests:** Add unit tests for: FDv2 requestor (e.g. poll with selector, response parsing, error handling); protocol handling (CHANGESET → apply, GOODBYE, errors); polling initializer and synchronizer invoking apply() correctly; builder options. Optionally one integration-style test with a mock HTTP server.

**Deliverable:** FDv2 polling initializer/synchronizer and builder; unit tests for requestor, protocol, and builder; can be used as building blocks for FDv2DataSource; existing FDv1 polling unchanged.

---

### **Part 6: Environment state and FDv2DataSource**

**Goal:** Enhance FDv2DataSource so that platform environment state (network, foreground/background, etc.) can be piped in and used to temporarily enable or disable initializers and synchronizers. Support patterns such as: streaming synchronizer only when in foreground, polling synchronizer when foreground or background. Refactor existing Android SDK code that handles network and background state so it is consistent and compatible with FDv2, while keeping FDv1 data sources working without breaking changes.

**Context:** Client SDKs run on platforms with environment state (e.g. Android: network state, foreground/background, offline). The server SDK has no equivalent. We need FDv2DataSource to react to these states so that, for example, defaultMode() can use streaming only in the foreground and fall back to polling in the background, without requiring the app to swap the DataSource.

**Actions:**

- **Environment state input to FDv2DataSource:** Define a way to feed environment state into FDv2DataSource (e.g. a **PlatformState** or **EnvironmentState** abstraction that FDv2DataSource subscribes to, or callbacks that the SDK core invokes when state changes). State dimensions should include at least: **foreground vs background**, **network available vs unavailable**, and **offline** (app-set). FDv2DataSource uses this to decide which initializers/synchronizers are currently allowed to run.
- **Synchronizer/initializer eligibility by state:** Extend the FDv2DataSource and/or Initializer/Synchronizer contracts so that each synchronizer (and initializer) can declare under which environment states it may run (e.g. “foreground only”, “foreground or background”). FDv2DataSource then enables or disables them as state changes — e.g. when the app goes to background, temp disable the streaming synchronizer and allow only the polling synchronizer; when it returns to foreground, re-enable streaming.
- **Refactor existing state handling:** The Android SDK already has code for network state, foreground/background, and offline (e.g. **ConnectivityManager**, **PlatformState**, **streamEvenInBackground**, background poll interval). Refactor so that:
  - A single, consistent view of environment state is available to both FDv1 and FDv2 code paths.
  - FDv1 data sources (current streaming/polling) continue to behave as today: they are still chosen and managed by the existing logic (e.g. switch to polling when in background unless streamEvenInBackground). No breaking change to apps using only FDv1.
  - FDv2DataSource (and its synchronizers) consume the same state and apply the new “enable/disable by state” rules internally, so the SDK can support both FDv1 and FDv2 data sources in the same release.
- **Compatibility with FDv1:** Ensure that when the app uses `streamingDataSource()` or `pollingDataSource()` (FDv1), behavior is unchanged. When the app uses `defaultDataSourceFDv2()` (or the other FDv2 methods), the same underlying platform state drives FDv2DataSource’s decisions. Design the refactor so that state providers are shared and the only difference is how the active DataSource (FDv1 vs FDv2) reacts to state.
- **Unit tests:** Add unit tests for: FDv2DataSource with mock environment state — e.g. when state changes from foreground to background, streaming synchronizer is disabled and polling remains active; when returning to foreground, streaming is re-enabled; state provider (or callback) is invoked and FDv2DataSource reacts. Test eligibility rules (foreground-only vs foreground-or-background) for each synchronizer type. After refactoring shared state, add or update unit tests for FDv1 code paths to ensure behavior is unchanged.

**Deliverable:** FDv2DataSource enhanced to accept environment state and enable/disable initializers and synchronizers by state; streaming-only-in-foreground and polling-in-both supported; existing network/background state code refactored for consistency and shared use by FDv1 and FDv2; unit tests for state-driven behavior and FDv1 compatibility; FDv1 data sources unchanged from the app’s perspective.

---

### **Part 7: Single ConnectivityManager for FDv1 and FDv2 (Option 1)**

**Goal:** Keep one **ConnectivityManager** for both FDv1 and FDv2 data sources. The app passes a data source configurer (FDv1 or FDv2); ConnectivityManager does not care which. "Run at all?" stays in ConnectivityManager; FDv2DataSource handles foreground/background internally (Part 6) and must not be torn down on every foreground/background transition.

**Context:** This implements **Option 1** from the FDv1/FDv2 integration design: no ConnectivityManagerV2; the same manager owns the single active DataSource whether it is FDv1 (streaming/polling) or FDv2 (FDv2DataSource). FDv1 continues to use "rebuild on state change" (streaming builder returns polling when in background; CM tears down and rebuilds when `needsRefresh` is true). FDv2 uses "one instance, internal reaction" — FDv2DataSource subscribes to PlatformState and toggles synchronizers; CM should not replace it on foreground/background.

**Actions:**

- Keep **ConnectivityManager** as the single owner of the active DataSource. When the app uses an FDv2 configurer (e.g. `defaultDataSourceFDv2()`), the factory builds an **FDv2DataSource**; when the app uses FDv1 configurers (`streamingDataSource()`, `pollingDataSource()`), the factory builds the existing streaming or polling DataSource. No second manager type.
- **Run at all:** ConnectivityManager continues to apply the same "run at all?" logic (forced offline, network available, in-background + disableBackgroundUpdating). No change to when the SDK refuses to start or tears down the data source; this applies to both FDv1 and FDv2.
- **FDv1 path:** Leave unchanged. ConnectivityManager listens to network and foreground; on foreground change it calls `dataSource.needsRefresh(newInBackground, context)`; if true it tears down and rebuilds via the factory. Streaming builder may return polling when in background (unless `streamEvenInBackground`); polling builder uses background poll interval. No behavioral change for FDv1.
- **FDv2 path:** Implement **FDv2DataSource.needsRefresh(newInBackground, newContext)** so that it returns **false** when only foreground/background changed, and **true** when evaluation context changed (e.g. identify) or other cases requiring a full rebuild. Thus ConnectivityManager does *not* tear down and rebuild FDv2DataSource on foreground/background; FDv2DataSource (Part 6) subscribes to PlatformState and enables/disables streaming vs polling synchronizers internally. ConnectivityManager only rebuilds FDv2DataSource on context switch or after going offline/disabled and coming back.
- Ensure FDv2DataSource receives **PlatformState** when built (e.g. from `ClientContextImpl.get(context).getPlatformState()`) so it can subscribe in Part 6; Part 7 focuses on the integration so that CM and FDv2DataSource work together (needsRefresh contract and wiring).
- **Unit tests:** Add unit tests for: ConnectivityManager with an FDv2 data source factory — when foreground→background or background→foreground, FDv2DataSource is *not* torn down (needsRefresh returns false for background-only change); when evaluation context changes, FDv2DataSource *is* torn down and rebuilt (needsRefresh returns true). Verify "run at all" behavior (offline, no network, background disabled) still applies when using an FDv2 configurer. Verify FDv1 path unchanged (e.g. streaming→polling on background when needsRefresh true).

**Deliverable:** Single ConnectivityManager used for both FDv1 and FDv2; FDv2DataSource.needsRefresh contract ensures no unnecessary rebuild on foreground/background; FDv1 behavior unchanged; unit tests for CM + FDv2 and CM + FDv1.

---

### **Part 8: Configuration and default behavior**

**Goal:** Expose FDv2 via new Components methods that provide pre-configured DataSources. The app chooses FDv2 by passing one of the new data sources to `LDConfig.Builder.dataSource(...)`.

**Actions:**

- Add three new **Components** methods. Each returns a `ComponentConfigurer<DataSource>` that builds a single **DataSource**;
  - **Components.defaultDataSourceFDv2()** — FDv2 data source that mirrors the server’s **DataSystemBuilder.defaultMode()**: polling initializer, then streaming and polling as synchronizers, then FDv1 fallback synchronizer. Same two-phase strategy: initial fetch via polling, then streaming (with polling fallback if streaming is interrupted), then FDv1 fallback when instructed by LaunchDarkly.
  - **Components.streamingDataSourceFDv2()** — FDv2 data source that mirrors the server’s **DataSystemBuilder.streaming()**: streaming as the sole synchronizer (no polling initializer), with FDv1 fallback. Suited for cases where streaming-first is preferred; LaunchDarkly can still instruct fallback to FDv1 polling.
  - **Components.pollingDataSourceFDv2()** — FDv2 data source that mirrors the server’s **DataSystemBuilder.polling()**: polling as the sole synchronizer, with FDv1 fallback. Suited for environments where streaming is not desired.
- Each of these is implemented by building an **FDv2DataSource** (Part 3, enhanced in Part 6) with the appropriate initializers and synchronizers (and FDv1 fallback). Part 6 ensures environment state (foreground/background, network, offline) is applied so that, for example, defaultDataSourceFDv2() uses streaming only in the foreground and polling in both foreground and background. Part 7 ensures the same **ConnectivityManager** owns this FDv2DataSource (no separate manager). The Android SDK does not expose **DataSystemBuilder** or **DataSystemModes**; the “mode” behavior is encapsulated in these three ComponentConfigurers.
- **LDConfig**: The app opts into FDv2 by calling e.g. `dataSource(Components.defaultDataSourceFDv2())` (or `streamingDataSourceFDv2()` / `pollingDataSourceFDv2()`). Existing `streamingDataSource()` and `pollingDataSource()` remain for FDv1 behavior.
- In **ComponentsImpl**, implement the three new methods (each returns a configurer that builds an FDv2DataSource with the right initializer/synchronizer/fallback configuration and wires in environment state from Part 6). ConnectivityManager (Part 7) will use this configurer like any other; no special case beyond the needsRefresh contract already implemented in Part 7.
- Document that Android does not have a full DataSystem; the three FDv2 methods are the supported “modes” and use FDv2DataSource under the hood. Document any endpoint or capability differences (e.g. segments, server-side-only options).
- Decide rollout: whether the default data source (when the app does not call `dataSource(...)`) remains FDv1 streaming or is switched to e.g. `defaultDataSourceFDv2()` after sufficient testing.
- **Unit tests:** Add unit tests for: each of the three Components methods returns a non-null configurer that, when used with LDConfig.Builder.dataSource(...), produces a DataSource of the expected type (FDv2DataSource) with the correct configuration (default = polling init + streaming + polling sync + FDv1 fallback; streaming = streaming sync + FDv1 fallback; polling = polling sync + FDv1 fallback); LDConfig accepts the FDv2 data source and client can be built without error. Use mocks for heavy dependencies where appropriate.

**Deliverable:** Components.defaultDataSourceFDv2(), Components.streamingDataSourceFDv2(), and Components.pollingDataSourceFDv2(); no useFDv2 flag; apps opt in via dataSource(...); unit tests for configurers and LDConfig wiring; docs updated.

---

### **Part 9: Fallback, recovery, and polish (optional / follow-up)**

**Goal:** Align with server behavior where it matters (goodbye, fallback, diagnostics).

**Actions:**

- **Goodbye handling:** When server sends GOODBYE, implement the recommended behavior (e.g. reconnect with new selector or switch to FDv1 fallback if we add it).
- **FDv1 fallback (optional):** If product requires it, add a path to fall back to current streaming/polling when FDv2 indicates fallback (e.g. after certain errors or goodbye); keep the single-DataSource model (switch implementation, do not add full DataSystem).
- **Headers and diagnostics:** Pass through any FDv2-required headers (e.g. If-None-Match / selector); ensure connection/status is reported correctly for diagnostics.
- **Unit tests:** Add or extend unit tests for: GOODBYE handling (e.g. FDv2DataSource or synchronizer reacts and switches/reconnects as designed); header construction and selector usage in requests; status reporting. Keep tests focused on the new behavior; use mocks for network/server where possible.
- **Contract and integration tests:** Add or extend contract tests so that FDv2 streaming and FDv2 polling are exercised against the test service if it supports FDv2.

**Deliverable:** Robust FDv2 behavior with unit tests for goodbye, headers, and status; contract/integration tests for FDv2 where supported; no new public API unless we explicitly add fallback options.

---

## 3. What not to port (or to simplify)

- **Full DataSystem / DataSystemModes:** Do not port the full server-style DataSystem abstraction (multiple modes, persistent store mode config) unless needed. Part 3 adds FDv2DataSource with initializers and synchronizers; that is the extent of orchestration planned.
- **Segments:** Server FDv2 has segments; Android client does not. When building Android `ChangeSet`s from FDv2, handle only kind `"flag"` unless we add segment support later.
- **DataSystemModes / persistent store modes:** Server EAP “data saving mode” and persistent store configuration are out of scope for this port unless we explicitly add them later.
- **File data source / TestDataV2:** Only port if we need SDK test or test-data support for FDv2; not required for core client behavior.

---

## 4. Configuration options to expose (recommendation)

- **FDv2 data sources:** Three pre-configured options on **Components** — **defaultDataSourceFDv2()**, **streamingDataSourceFDv2()**, **pollingDataSourceFDv2()**. The app passes the desired one to **LDConfig.Builder.dataSource(...)**.
- **Streaming / polling options:** Same as today where applicable (e.g. initial reconnect delay, stream in background, poll interval, custom endpoints) — these may be exposed on the FDv2 data source builders or as builder options for the three ComponentConfigurers as needed.
- Do **not** expose: **useFDv2**, full **DataSystemBuilder** / **DataSystemModes**, persistent store mode, or low-level initializer/synchronizer lists (the three Components methods encapsulate those).

---

## 5. Dependency summary

- **Part 1** is required for all later parts.
- **Part 2** (TransactionalDataSourceUpdateSink and DataSourceUpdateSinkV2 with apply) is required for Part 3, Part 4, and Part 5.
- **Part 3** (FDv2DataSource and Initializer/Synchronizer interfaces) comes next; it depends only on Part 2. The orchestrator is implemented and tested with mocks/stubs before concrete synchronizers exist.
- **Part 4** (FDv2 streaming synchronizer/initializer) and **Part 5** (FDv2 polling initializer/synchronizer) implement the Part 3 contracts and plug into FDv2DataSource; both depend on Part 2 and Part 3. Part 4 and Part 5 can be done in either order.
- **Part 6** (environment state and FDv2DataSource) depends on Parts 2, 3, 4, and 5. It enhances FDv2DataSource so environment state (foreground/background, network, offline) can be piped in and used to enable/disable initializers and synchronizers. It also refactors existing network/background state code for consistency and FDv1/FDv2 compatibility.
- **Part 7** (single ConnectivityManager for FDv1 and FDv2 — Option 1) depends on Part 6. It keeps one ConnectivityManager for both; FDv2DataSource implements needsRefresh so it is not torn down on foreground/background (Part 6 handles that internally); FDv1 path unchanged.
- **Part 8** (configuration) depends on Parts 3, 4, 5, 6, and 7 — the three Components methods build FDv2DataSource (with Part 6 state behavior and Part 7 CM integration) using the Part 4 and Part 5 implementations.
- **Part 9** builds on Parts 3–8.

This sequence implements the FDv2DataSource orchestrator before the specific streaming and polling implementations, adds environment-state awareness and FDv1-compatible refactoring, integrates with the single ConnectivityManager (Option 1), then exposes the three FDv2 modes via configuration.

# SDK-1956: `switchMode()` Approach Comparison

## Background

`FDv2DataSource` needs a mechanism to change its active synchronizers at runtime without re-running initializers. This document compares two approaches for the `switchMode()` method and proposes a hybrid.

### Spec Requirement: CONNMODE 2.0.1

> When switching modes after initialization is complete, the SDK **MUST** only transition to the new mode's synchronizer list. The SDK **MUST NOT** re-run the initializer chain.
>
> Initializers run during initial startup and when `identify` is called with a new context — they are responsible for obtaining a first full data set. Once the SDK is initialized for a context, mode transitions only change which synchronizers are active. For example, switching from `"streaming"` to `"background"` stops the streaming and polling synchronizers and starts the background polling synchronizer; it does not re-run the cache or polling initializers.

This requirement means a simple teardown/rebuild of `FDv2DataSource` on mode change is not viable — it would re-run initializers, violating the spec.

### Spec Requirement: CONNMODE 2.0.2

> When `identify` is called with a new context, the SDK **MUST** run the initializer chain of the currently active mode for that new context, followed by activating its synchronizers.

This is the *only* case where initializers re-run after first startup. The current mode is retained across `identify` calls.

---

## Approach 1: `switchMode(ConnectionMode)` — pass the enum

FDv2DataSource holds the full mode table internally (`Map<ConnectionMode, ResolvedModeDefinition>`). When it receives `switchMode(BACKGROUND)`, it looks up the definition in its own table and swaps synchronizers.

**Construction:**
```java
FDv2DataSource(
    evaluationContext,
    modeTable,           // Map<ConnectionMode, ResolvedModeDefinition>
    startingMode,        // ConnectionMode.STREAMING
    dataSourceUpdateSink,
    sharedExecutor,
    logger
)
```

The builder resolves all `ComponentConfigurer` → `DataSourceFactory` conversions upfront (by applying `ClientContext`) and hands the complete resolved table to FDv2DataSource.

## Approach 2: `switchMode(ResolvedModeDefinition)` — pass the definition

FDv2DataSource has no mode table. It receives "here are your new synchronizer factories" each time. The mode table lives externally — in ConnectivityManager or a resolver object.

**Construction:**
```java
FDv2DataSource(
    evaluationContext,
    initialInitializers,   // List<DataSourceFactory<Initializer>>
    initialSynchronizers,  // List<DataSourceFactory<Synchronizer>>
    dataSourceUpdateSink,
    sharedExecutor,
    logger
)
```

This is essentially Todd's existing constructor signature. `switchMode()` just receives new factory lists.

---

## Comparison

| Dimension | Approach 1 (enum) | Approach 2 (definition) |
|-----------|-------------------|------------------------|
| **API clarity** | Very clean — one enum value, no internals exposed | Caller must construct/lookup ModeDefinition objects |
| **Encapsulation** | ConnectivityManager only knows about named modes; FDv2 internals stay inside FDv2DataSource | FDv2 concepts (factory lists, initializer/synchronizer structure) leak into ConnectivityManager or a resolver |
| **Spec alignment** | Matches the spec's model — modes are named concepts that the data system resolves (CSFDV2 Section 5.3) | Flattens the spec's layered abstraction; the caller does both resolution and lookup |
| **CONNMODE 2.0.1 compliance** | FDv2DataSource internally enforces "no re-run initializers" — it knows the difference between mode switch and startup | Caller must ensure that only synchronizer factories are passed, not initializers — the constraint is externalized and unenforceable by FDv2DataSource |
| **CONNMODE 2.0.2 (identify)** | On identify, FDv2DataSource can re-run the current mode's initializers because it holds the full mode table with both initializer and synchronizer entries | Caller must pass both initializers and synchronizers for the current mode during identify — FDv2DataSource can't do this on its own |
| **Logging/diagnostics** | Easy: "switching to BACKGROUND" | Harder to log meaningfully — need to track mode name separately |
| **Validation** | FDv2DataSource validates the mode exists in its table at call time | No validation possible — takes whatever it's given |
| **Current mode tracking** | FDv2DataSource knows its current `ConnectionMode` — useful for `needsRefresh()` decisions, diagnostics, CONNMODE 3.5.3 no-op check ("if desired == actual, no action") | FDv2DataSource has no concept of "what mode am I in" |
| **Backward compatibility** | New constructor — Todd's tests need a compatibility constructor | Constructor matches Todd's existing signature closely |
| **Flexibility** | Constrained to the predefined mode table; custom ad-hoc definitions can't be passed | Fully flexible — any factory list can be passed at any time |
| **Who holds the mode table** | FDv2DataSource (natural home — it's the component that uses the definitions) | Must live externally — ConnectivityManager or a separate resolver, adding coordination |

### Memory

Not meaningfully different. The mode table holds 5 entries, each containing a few lambda references (factories that close over shared dependencies like `HttpProperties`, `SelectorSource`). The actual `Initializer`/`Synchronizer` objects are only created when `build()` is called on the factory. Both approaches hold the same total objects — the question is just where they live.

### Benefits specific to the enum

1. **No-op detection:** CONNMODE 3.5.3 says "take the minimal set of actions to reconcile the current actual state with the desired state." With an enum, `switchMode()` can trivially check `if (newMode == currentMode) return;`. With a definition, you'd need reference equality or a separate mode tracker.

2. **`needsRefresh()` awareness:** FDv2DataSource can inspect its current mode to make decisions. For example, knowing it's in `OFFLINE` mode is different from knowing it has zero synchronizers — the former is semantic, the latter is incidental.

3. **Identify support (CONNMODE 2.0.2):** When `identify()` triggers a data system restart, FDv2DataSource needs to know *which mode* to restart with (run that mode's initializers for the new context). With the enum and mode table, it has this information. With Approach 2, the caller must supply both initializer and synchronizer lists.

4. **Diagnostics:** Connection status reporting, debug logging, and future telemetry all benefit from knowing the current named mode rather than an opaque list of factories.

---

---

## Network Availability and "Pause/Resume" via Mode Switching

### The spec's language

CONNMODE 3.2.5 says:

> Network availability changes **MUST NOT** trigger a mode change. When the network becomes unavailable, active synchronizers and initializers **MUST** be paused (no new connection attempts). When the network becomes available, they **MUST** be resumed.

This language uses "pause/resume" and explicitly says network loss is NOT a mode change. This raises the question: do we need separate `pause()` / `resume()` methods on `FDv2DataSource`?

### What js-core actually implements

**No.** Across all of Ryan's FDv2 branches — including the latest (`rlamb/sdk-1926/connection-mode-switching-definition`) — there is no `pause()` or `resume()` anywhere. The word "pause" only appears in documentation comments, never in code interfaces.

Network loss is handled entirely through the mode resolution table:

```typescript
const MOBILE_TRANSITION_TABLE: ModeResolutionTable = [
  { conditions: { networkAvailable: false }, mode: 'offline' },
  { conditions: { lifecycle: 'background' }, mode: { configured: 'background' } },
  { conditions: { lifecycle: 'foreground' }, mode: { configured: 'foreground' } },
];
```

Network goes down → table resolves to `'offline'`. Network comes back → table re-evaluates based on lifecycle → resolves to the appropriate mode.

### Why `switchMode(OFFLINE)` ≡ `pause()`

The OFFLINE mode definition is:
- Initializers: `[cache]`
- Synchronizers: `[]` (none)

So `switchMode(OFFLINE)` does exactly what "pause" means:
1. Stop all current synchronizers (no more network requests)
2. Do NOT re-run initializers (per CONNMODE 2.0.1)
3. FDv2DataSource remains alive and initialized
4. The current mode is now OFFLINE — tracked by the enum

And `switchMode(previousMode)` ≡ `resume()`:
1. Start the previous mode's synchronizers
2. Do NOT re-run initializers (per CONNMODE 2.0.1)
3. FDv2DataSource was never torn down, so all initialization state is preserved

The spec's "pause/resume" language describes the **behavioral outcome**, but the **mechanism** is mode switching. This is exactly what js-core implements.

### How `start()` / `stop()` differ from this

FDv2DataSource already has `start()` and `stop()` — these are **full lifecycle** operations, fundamentally different from mode switching:

| | `start()` | `stop()` | `switchMode(OFFLINE)` |
|--|-----------|----------|----------------------|
| **Purpose** | Full lifecycle startup | Full lifecycle teardown | Pause synchronizers |
| **Initializers** | Runs the full chain | N/A | Does NOT re-run (CONNMODE 2.0.1) |
| **Synchronizers** | Starts loop after init | Closes SourceManager | Stops current, starts OFFLINE's (none) |
| **State after** | Running, initialized | Dead — no recovery | Alive, initialized, paused |
| **Finality** | Called once per lifetime | Terminal — must construct new instance | Reversible — `switchMode(X)` resumes |

`stop()` sets `stopped = true` — the data source is dead. A new `FDv2DataSource` must be constructed to restart. This is why `ConnectivityManager` does a full teardown/rebuild on context switch.

`switchMode(OFFLINE)` preserves the data source in a running but idle state. The mode resolution table naturally handles the transition back to an active mode when conditions change.

### Edge case: background + network loss

Consider the sequence: app is STREAMING → network drops → app backgrounds.

With mode switching via the resolution table, the debounced state resolves as:
- `networkAvailable: false` → first match → `OFFLINE`

When the network returns while still backgrounded:
- `networkAvailable: true, lifecycle: background` → second match → configured background mode (e.g., `BACKGROUND`)

When the app foregrounds with network:
- `networkAvailable: true, lifecycle: foreground` → third match → configured foreground mode (e.g., `STREAMING`)

The mode resolution table handles all combinations of network + lifecycle state correctly through a single `switchMode()` call. No separate pause/resume tracking is needed.

### Conclusion

Separate `pause()` / `resume()` methods are unnecessary. `switchMode(ConnectionMode)` handles network state changes naturally through the mode resolution table, achieving the behavioral outcome the spec describes as "pause/resume" without requiring a separate API surface.

---

## Recommendation

**Approach 1 (enum)** is the stronger choice. It aligns with the spec's layered architecture (CSFDV2 5.3 defines modes as named concepts), keeps FDv2 internals encapsulated, enables self-enforcement of CONNMODE 2.0.1 (no initializer re-run), and provides the semantic awareness needed for no-op detection, identify restarts, and diagnostics. It also naturally handles the spec's "pause/resume" requirement for network changes via `switchMode(OFFLINE)` / `switchMode(previousMode)`, without requiring additional API surface.

---

## Spec References

All spec references are from the draft branch `rlamb/client-side-fdv2` in `launchdarkly/sdk-specs`.

- **CONNMODE 2.0.1:** Mode switch → synchronizer-only transition; MUST NOT re-run initializers
- **CONNMODE 2.0.2:** Identify → re-run current mode's initializers for new context
- **CONNMODE 3.2.5:** Network availability changes MUST NOT trigger a mode change; synchronizers MUST be paused/resumed
- **CONNMODE 3.5.3:** Debounce resolution → take minimal action to reconcile desired vs actual state
- **CONNMODE 3.5.4:** Debounce window SHOULD be 1 second
- **CSFDV2 5.3:** Named connection mode definitions (streaming, polling, offline, one-shot, background)
- **CSFDV2 6.1.1:** Current mode retained across identify calls

## js-core References

- **Mode resolution table:** `rlamb/sdk-1926/connection-mode-switching-definition` branch — `ModeResolver.ts`, `ModeResolution.ts`
- **MOBILE_TRANSITION_TABLE:** Maps `{ networkAvailable: false } → 'offline'` — network loss handled as mode switch, not separate pause
- **FDv2DataSource deletion:** This branch deletes `FDv2DataSource.ts`, `SourceManager.ts`, `Conditions.ts` — Ryan is restructuring the orchestrator around mode resolution
- **No pause/resume API exists** anywhere in js-core's FDv2 datasource code

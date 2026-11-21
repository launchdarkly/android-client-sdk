# LaunchDarkly Android SDK test app

This app is an internal quality harness. For customer integration examples, use `example`.

Add the following to the repository's root `local.properties`:

```properties
launchdarkly.mobileKey=mob-...
launchdarkly.environment=production
```

Set `launchdarkly.environment=staging` to use LaunchDarkly's staging endpoints.

## Tier 1 event-loss scenario

Create a boolean flag named `kill-flag`, or enter another flag key in the app. Tap
**Eval+track+kill** to evaluate the flag, track a stand-in error event, request a flush, and
terminate the process five seconds later. This exercises the interval between recording and
delivery without Android lifecycle callbacks masking the result.

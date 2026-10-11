package com.launchdarkly.seat;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.net.URI;

/**
 * The seat-side hook translation for the CP/stdio wheel (CP-SPEC §7).
 *
 * The SDK's hook stages arrive here through the harness's own adapter shape
 * ({@code TestHook}); a configured {@code cp-callback://<channel>/<token>}
 * callback URI is emitted as a CP {@code callback} envelope and awaited —
 * CP-SPEC 7.2: the seat MUST NOT dial cp-callback URIs, and MUST NOT complete
 * the SDK-side call before the runner's reply arrives. The hook POST's body
 * maps 1:1 to the harness hookPayload fields
 * ({stage, evaluationSeriesContext, evaluationSeriesData, evaluationDetail}
 * for evaluation stages; {stage, trackSeriesContext} for afterTrack).
 */
final class SeatCpCallbackService {
  private final CpCallbackBus bus;

  SeatCpCallbackService(CpCallbackBus bus) {
    this.bus = bus;
  }

  /** True when the configured callback URI belongs to the CP/stdio wheel. */
  static boolean isCpUri(URI uri) {
    return uri != null && CpCallbackBus.SCHEME.equals(uri.getScheme());
  }

  /** Posts a hook stage: payload = the curated HookPayloadBuilder walk (hookPayload). */
  void postHookStage(URI callbackUri, Object params) {
    bus.post("hook", callbackUri, null, HookPayloadBuilder.build(params));
  }
}

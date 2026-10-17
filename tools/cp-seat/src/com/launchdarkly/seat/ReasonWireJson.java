package com.launchdarkly.seat;

import com.google.gson.JsonObject;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;

import com.launchdarkly.sdk.EvaluationReason;

import java.lang.reflect.Type;

/**
 * Serializes an evaluation reason with the client-side wire spellings
 * ({@code isExperiment}, {@code prereqKey}) — the shape the campaign's client N0
 * corpus, the harness's own data-file parameters, and the documented LD client
 * grammar all pin. The decode side (the SDK's DataModel adapter) dual-accepts
 * both spellings; this side normalizes to the wire.
 */
final class ReasonWireJson implements JsonSerializer<EvaluationReason> {
  @Override
  public JsonObject serialize(EvaluationReason reason, Type typeOfSrc, JsonSerializationContext context) {
    if (reason == null) {
      return null;
    }
    JsonObject o = new JsonObject();
    o.addProperty("kind", reason.getKind().name());
    switch (reason.getKind()) {
      case FALLTHROUGH:
        if (reason.isInExperiment()) {
          o.addProperty("isExperiment", true);
        }
        break;
      case TARGET_MATCH:
        break;
      case RULE_MATCH:
        o.addProperty("ruleIndex", reason.getRuleIndex());
        if (reason.getRuleId() != null) {
          o.addProperty("ruleId", reason.getRuleId());
        }
        if (reason.isInExperiment()) {
          o.addProperty("isExperiment", true);
        }
        break;
      case PREREQUISITE_FAILED:
        if (reason.getPrerequisiteKey() != null) {
          o.addProperty("prereqKey", reason.getPrerequisiteKey());
        }
        break;
      case ERROR:
        if (reason.getErrorKind() != null) {
          o.addProperty("errorKind", reason.getErrorKind().name());
        }
        if (reason.getBigSegmentsStatus() != null) {
          o.addProperty("bigSegmentsStatus", reason.getBigSegmentsStatus().name());
        }
        break;
      default:
        break;
    }
    return o;
  }
}

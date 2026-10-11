package com.launchdarkly.seat;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import com.launchdarkly.sdk.EvaluationDetail;
import com.launchdarkly.sdk.LDContext;
import com.launchdarkly.sdk.LDValue;
import com.launchdarkly.sdk.json.JsonSerialization;

import java.lang.reflect.Field;
import java.util.Map;

/**
 * Builds the hookPayload rows ({stage, evaluationSeriesContext,
 * evaluationSeriesData, evaluationDetail}) with a CURATED field walk rather than
 * whole-object Gson reflection: the Android hook's seriesContext carries an
 * EvaluationExposureKeySupplier lambda whose referent gson reflection can't touch
 * (`java.lang.ref.Reference#referent` accessibility bites), and the reflective
 * failure would break every hook stage the wheel emits. The curated fields are
 * exactly the harness hookPayload contract (cp.schema: flagKey, context, method,
 * defaultValue; LDContext/LDValue via the SDK's own JsonSerialization).
 */
final class HookPayloadBuilder {
  private HookPayloadBuilder() {}

  static JsonNull orNull(Object o) {
    return null; // placeholder removed below
  }

  /** Payload builder for the harness param objects (reflection on the params' own fields only). */
  static com.google.gson.JsonElement build(Object params) {
    com.google.gson.JsonObject o = new com.google.gson.JsonObject();
    if (params == null) {
      return o;
    }
    try {
      putString(o, params, "stage");
      if (readField(params, "evaluationSeriesContext") != null) {
        Object ctx = readField(params, "evaluationSeriesContext");
        o.add("evaluationSeriesContext", seriesContext(ctx));
      }
      Object seriesData = readField(params, "evaluationSeriesData");
      o.add("evaluationSeriesData", seriesData == null ? JsonNull.INSTANCE
          : SeatServe.gson.toJsonTree(seriesData));
      Object detail = readField(params, "evaluationDetail");
      if (detail != null) {
        o.add("evaluationDetail", evaluationDetail(detail));
      }
      Object trackCtx = readField(params, "trackSeriesContext");
      if (trackCtx != null) {
        o.add("trackSeriesContext", SeatServe.gson.toJsonTree(trackCtx));
      }
    } catch (Exception e) {
      throw new IllegalStateException("hook payload build failed: " + SeatServe.describe(e), e);
    }
    return o;
  }

  private static com.google.gson.JsonObject seriesContext(Object ctx) {
    com.google.gson.JsonObject c = new com.google.gson.JsonObject();
    try {
      Object method = readField(ctx, "method");
      if (method != null) c.addProperty("method", String.valueOf(method));
      Object flagKey = readField(ctx, "flagKey");
      if (flagKey != null) c.addProperty("flagKey", String.valueOf(flagKey));
      Object lctx = readField(ctx, "context");
      if (lctx instanceof LDContext) {
        c.add("context", SeatServe.gson.toJsonTree(JsonSerialization.serialize((LDContext) lctx)));
      } else if (lctx != null) {
        c.add("context", SeatServe.gson.toJsonTree(lctx));
      } else {
        c.add("context", JsonNull.INSTANCE);
      }
      Object dv = readField(ctx, "defaultValue");
      if (dv != null) {
        c.add("defaultValue", SeatServe.gson.toJsonTree(dv));
      } else {
        c.add("defaultValue", JsonNull.INSTANCE);
      }
    } catch (Exception e) {
      throw new IllegalStateException("seriesContext build: " + SeatServe.describe(e), e);
    }
    return c;
  }

  private static com.google.gson.JsonObject evaluationDetail(Object detail) {
    com.google.gson.JsonObject d = new com.google.gson.JsonObject();
    try {
      Object value = readField(detail, "value");
      if (value != null) {
        d.add("value", SeatServe.gson.toJsonTree(value));
      } else {
        d.add("value", JsonNull.INSTANCE);
      }
      Object vi = readField(detail, "variationIndex");
      if (vi instanceof Integer) {
        d.addProperty("variationIndex", ((Integer) vi).intValue());
      } else {
        d.add("variationIndex", JsonNull.INSTANCE);
      }
      Object reason = readField(detail, "reason");
      if (reason != null) {
        d.add("reason", ReasonWireJsonHolder.INST.serialize(
            (com.launchdarkly.sdk.EvaluationReason) reason,
            com.launchdarkly.sdk.EvaluationReason.class, null));
      } else {
        d.add("reason", JsonNull.INSTANCE);
      }
    } catch (Exception e) {
      throw new IllegalStateException("evaluationDetail build: " + SeatServe.describe(e), e);
    }
    return d;
  }

  private static Object readField(Object owner, String name) {
    try {
      Field f = owner.getClass().getDeclaredField(name);
      f.setAccessible(true);
      return f.get(owner);
    } catch (NoSuchFieldException e) {
      return null;
    } catch (Exception e) {
      throw new IllegalStateException(name + ": " + SeatServe.describe(e), e);
    }
  }

  private static void putString(com.google.gson.JsonObject o, Object owner, String name) {
    Object v = readField(owner, name);
    if (v != null) {
      o.addProperty(name, String.valueOf(v));
    } else {
      o.add(name, JsonNull.INSTANCE);
    }
  }

  /** Singleton route so the wire spelling stays the one encode contract. */
  private static final class ReasonWireJsonHolder {
    static final ReasonWireJson INST = new ReasonWireJson();
  }
}

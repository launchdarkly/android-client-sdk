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
      if (hasField(params, "evaluationSeriesData")) {
        // The harness hookPayload member law: an evaluation/track hookPayload
        // carries its series data; the identify-series rows' payloads price
        // only the identify arm seats ({stage, identifySeriesContext,
        // identifySeriesData[, identifyResult]}) — the evaluation member
        // prices absent there (never a null placeholder).
        Object seriesData = readField(params, "evaluationSeriesData");
        o.add("evaluationSeriesData", seriesData == null ? JsonNull.INSTANCE
            : SeatServe.gson.toJsonTree(seriesData));
      }
      Object detail = readField(params, "evaluationDetail");
      if (detail != null) {
        o.add("evaluationDetail", evaluationDetail(detail));
      }
      Object trackCtx = readField(params, "trackSeriesContext");
      if (trackCtx != null) {
        // The cp.schema trackSeriesContext arm: {key (required), context,
        // data, metricValue} — never the evaluation shape.
        com.google.gson.JsonObject t = new com.google.gson.JsonObject();
        Object tkey = readField(trackCtx, "key");
        t.addProperty("key", tkey == null ? JsonNull.INSTANCE.toString() : String.valueOf(tkey));
        Object tctx = readField(trackCtx, "context");
        if (tctx != null) {
          t.add("context", SeatServe.gson.toJsonTree(tctx));
        } else {
          t.add("context", JsonNull.INSTANCE);
        }
        Object tdata = readField(trackCtx, "data");
        t.add("data", tdata == null ? JsonNull.INSTANCE : SeatServe.gson.toJsonTree(tdata));
        Object tmv = readField(trackCtx, "metricValue");
        if (tmv instanceof Number) {
          t.addProperty("metricValue", (Number) tmv);
        } else {
          t.add("metricValue", JsonNull.INSTANCE);
        }
        Object envId = readField(trackCtx, "environmentId");
        if (envId != null) {
          t.addProperty("environmentId", String.valueOf(envId));
        }
        o.add("trackSeriesContext", t);
      }
      Object idCtx = readField(params, "identifySeriesContext");
      // The identify-series payload law: the series-context seat prices the
      // beforeIdentify row only; the afterIdentify rows price {stage,
      // identifySeriesData, identifyResult} (the harness's own payload
      // member sets per stage).
      if (idCtx != null && "beforeIdentify".equals(String.valueOf(readField(params, "stage")))) {
        com.google.gson.JsonObject ic = new com.google.gson.JsonObject();
        Object ilctx = readField(idCtx, "context");
        if (ilctx != null) {
          ic.add("context", SeatServe.gson.toJsonTree(ilctx));
        } else {
          ic.add("context", JsonNull.INSTANCE);
        }
        o.add("identifySeriesContext", ic);
      }
      Object idData = readField(params, "identifySeriesData");
      if (idData != null) {
        o.add("identifySeriesData", SeatServe.gson.toJsonTree(idData));
      }
      Object idResult = readField(params, "identifyResult");
      if (idResult != null) {
        com.google.gson.JsonObject ir = new com.google.gson.JsonObject();
        try {
          Object status = readField(idResult, "status");
          if (status != null) {
            ir.add("status", SeatServe.gson.toJsonTree(status));
          }
        } catch (Exception ignored) {
        }
        if (ir.size() > 0) {
          o.add("identifyResult", ir);
        }
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
      if (method != null) c.addProperty("method", methodName(String.valueOf(method)));
      Object flagKey = readField(ctx, "flagKey");
      if (flagKey != null) c.addProperty("flagKey", String.valueOf(flagKey));
      Object lctx = readField(ctx, "context");
      if (lctx != null) {
        // cp.schema hookPayload: `context` rides an ldContext OBJECT (v3:L211),
        // never the serialized string.
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
      if (vi instanceof Integer && ((Integer) vi).intValue() != EvaluationDetail.NO_VARIATION) {
        // The harness's detail members: a real variation index rides its
        // value; a no-variation read prices the null member (the typed
        // default's own detail form).
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

  /** Harness method spelling: `LDClient.stringVariationDetail` -> `string_variation_detail`
   * (the v3 hook spec's `method` field names the EVALUATED method in the python-style
   * snake; the bank rows pin this shape). */
  static String methodName(String sdkMethod) {
    String s = sdkMethod;
    int dot = s.lastIndexOf('.');
    if (dot >= 0) {
      s = s.substring(dot + 1);
    }
    if (s.startsWith("jsonValue")) {
      // the harness's own method spelling: jsonValueVariationDetail ->
      // json_variation_detail (the Value segment is dropped, the python
      // family's own method name the rows pin)
      return "json_variation_detail";
    }
    StringBuilder b = new StringBuilder();
    for (int i = 0; i < s.length(); i++) {
      char ch = s.charAt(i);
      if (Character.isUpperCase(ch) && i > 0) {
        b.append('_');
      }
      b.append(Character.toLowerCase(ch));
    }
    return b.toString();
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

  /** The payload's member-set law distinguishes an absent param field (the
   * member prices ABSENT — never serialized) from a present-null field
   * (serialized as null): the identify rows' payloads never named the
   * evaluation member. */
  private static boolean hasField(Object owner, String name) {
    try {
      owner.getClass().getDeclaredField(name);
      return true;
    } catch (NoSuchFieldException e) {
      return false;
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

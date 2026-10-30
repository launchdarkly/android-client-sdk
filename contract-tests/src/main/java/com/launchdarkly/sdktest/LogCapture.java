package com.launchdarkly.sdktest;

import com.launchdarkly.logging.LDLogAdapter;
import com.launchdarkly.logging.LDLogLevel;
import com.launchdarkly.logging.LogValues;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * The x-cp-logs drain face (CP-SPEC 10): one ring of {level, code, message}
 * records riding the seat logging chain. The capture adapter wraps the base
 * adapter in TestService, so every SDK-facing log record (the seat's own
 * logger included) both prints through the base adapter and records here —
 * no SDK behavior changes, the records surface the seat's own log stream.
 *
 * NOT verifier-specific: the command maps CP onto the SDK's public logging
 * API (CP-SPEC 11 item 8). The record shape prices the python seat's own
 * precedent ({level, code, message}: code = the message's first four chars
 * upper-cased).
 */
public final class LogCapture {
  private final ConcurrentLinkedDeque<Map<String, Object>> records =
          new ConcurrentLinkedDeque<>();
  private final int limit;

  public LogCapture(int limit) {
    this.limit = limit;
  }

  public List<Map<String, Object>> drain() {
    List<Map<String, Object>> out = new ArrayList<>();
    Map<String, Object> row;
    while ((row = records.poll()) != null) {
      out.add(row);
    }
    return out;
  }

  void add(LDLogLevel level, String message) {
    java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
    row.put("level", level == null ? "info" : level.name().toLowerCase());
    row.put("code", message != null
            ? message.substring(0, Math.min(4, message.length())).toUpperCase()
            : "");
    row.put("message", message == null ? "" : message);
    records.add(row);
    while (records.size() > limit) {
      records.poll();
    }
  }

  /** LDLogAdapter that records every log call and forwards to the base
   * adapter's own channel (the seat's log stream keeps printing raw). */
  public static final class CaptureLogAdapter implements LDLogAdapter {
    private final LDLogAdapter base;
    private final LogCapture capture;

    public CaptureLogAdapter(LDLogAdapter base, LogCapture capture) {
      this.base = base;
      this.capture = capture;
    }

    @Override
    public LDLogAdapter.Channel newChannel(String name) {
      return new CaptureChannel(base.newChannel(name), capture);
    }
  }

  private static final class CaptureChannel implements LDLogAdapter.Channel {
    private final LDLogAdapter.Channel base;
    private final LogCapture capture;

    CaptureChannel(LDLogAdapter.Channel base, LogCapture capture) {
      this.base = base;
      this.capture = capture;
    }

    private static String render(String message, Object... args) {
      if (message == null) {
        return "";
      }
      if (args == null || args.length == 0) {
        return message;
      }
      // the logging lib's "{}" substitution face: the record's message prices
      // the rendered line the SDK actually logged
      StringBuilder out = new StringBuilder(message.length() + 40);
      int argIndex = 0;
      int i = 0;
      while (i < message.length()) {
        int brace = message.indexOf("{}", i);
        if (brace < 0) {
          out.append(message, i, message.length());
          break;
        }
        out.append(message, i, brace);
        if (argIndex < args.length) {
          Object arg = args[argIndex++];
          out.append(arg instanceof LogValues.StringProvider
                  ? ((LogValues.StringProvider) arg).get()
                  : String.valueOf(arg));
        }
        i = brace + 2;
      }
      return out.toString();
    }

    private void record(LDLogLevel level, String message, Object[] args) {
      capture.add(level, render(message, args != null ? args : new Object[0]));
    }

    @Override
    public boolean isEnabled(LDLogLevel level) {
      return base.isEnabled(level);
    }

    @Override
    public void log(LDLogLevel level, Object message) {
      capture.add(level, message instanceof LogValues.StringProvider
              ? ((LogValues.StringProvider) message).get()
              : String.valueOf(message));
      base.log(level, message);
    }

    @Override
    public void log(LDLogLevel level, String message, Object arg1) {
      record(level, message, new Object[] {arg1});
      base.log(level, message, arg1);
    }

    @Override
    public void log(LDLogLevel level, String message, Object arg1, Object arg2) {
      record(level, message, new Object[] {arg1, arg2});
      base.log(level, message, arg1, arg2);
    }

    @Override
    public void log(LDLogLevel level, String message, Object... args) {
      record(level, message, args);
      base.log(level, message, args);
    }
  }
}
